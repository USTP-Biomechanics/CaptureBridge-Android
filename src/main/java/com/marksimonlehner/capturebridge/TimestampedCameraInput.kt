package com.marksimonlehner.capturebridge

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class TimestampedCameraInput(
    private val codecInputSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val onFrameSubmitted: (Long) -> Unit
) {
    private val frameLock = Object()
    private val texMatrix = FloatArray(16)
    private val vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }
    private val texBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(TEX_COORDS)
            position(0)
        }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var pendingFrames = 0
    private var stopped = false

    fun start(): Surface {
        val inputThread = HandlerThread("rolling-video-timestamp-input")
        inputThread.start()
        val inputHandler = Handler(inputThread.looper)
        thread = inputThread
        handler = inputHandler

        val latch = CountDownLatch(1)
        var resultSurface: Surface? = null
        var setupError: Throwable? = null
        inputHandler.post {
            try {
                resultSurface = setupOnInputThread()
            } catch (error: Throwable) {
                setupError = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("Timed out creating timestamped encoder input")
        }
        setupError?.let {
            stop()
            throw IllegalStateException("Could not create timestamped encoder input", it)
        }
        return resultSurface ?: throw IllegalStateException("Timestamped encoder input did not create a surface")
    }

    fun stop() {
        stopped = true
        val inputHandler = handler
        val latch = CountDownLatch(1)
        if (inputHandler != null) {
            inputHandler.post {
                try {
                    releaseOnInputThread()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
        } else {
            codecInputSurface.release()
        }
        thread?.quitSafely()
        try {
            thread?.join(800)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        handler = null
    }

    private fun setupOnInputThread(): Surface {
        setupEgl()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        textureId = createExternalTexture()
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            synchronized(frameLock) {
                pendingFrames += 1
            }
            handler?.post { renderPendingFrames() }
        }, handler)
        surfaceTexture = st
        return Surface(st).also { cameraSurface = it }
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("No EGL display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("Could not initialize EGL")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw IllegalStateException("Could not choose EGL config")
        }
        val config = configs[0] ?: throw IllegalStateException("No EGL config")
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("Could not create EGL context")
        }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            codecInputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Could not create EGL window surface")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("Could not make EGL context current")
        }
        GLES20.glViewport(0, 0, width, height)
    }

    private fun renderPendingFrames() {
        while (!stopped) {
            synchronized(frameLock) {
                if (pendingFrames <= 0) {
                    return
                }
                pendingFrames -= 1
            }
            renderOneFrame()
        }
    }

    private fun renderOneFrame() {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        val timestampNs = st.timestamp
        if (timestampNs <= 0L) {
            throw IllegalStateException("Camera frame timestamp is not ready")
        }
        st.getTransformMatrix(texMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw IllegalStateException("Could not swap timestamped encoder input frame")
        }
        onFrameSubmitted(timestampNs / 1000L)
    }

    private fun releaseOnInputThread() {
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
        } catch (_: Exception) {
        }
        try {
            cameraSurface?.release()
        } catch (_: Exception) {
        }
        cameraSurface = null
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        codecInputSurface.release()
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val nextProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(nextProgram, vertexShader)
        GLES20.glAttachShader(nextProgram, fragmentShader)
        GLES20.glLinkProgram(nextProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(nextProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(nextProgram)
            GLES20.glDeleteProgram(nextProgram)
            throw IllegalStateException("Could not link GL program: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return nextProgram
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Could not compile GL shader: $log")
        }
        return shader
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        val VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val TEX_COORDS = floatArrayOf(
            0f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f
        )
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
