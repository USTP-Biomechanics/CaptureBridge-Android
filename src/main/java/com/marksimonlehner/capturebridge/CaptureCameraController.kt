package com.marksimonlehner.capturebridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaCodec
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class CameraPosition {
    Front,
    Back
}

data class CameraUiState(
    val isRecording: Boolean = false,
    val isTransferring: Boolean = false,
    val captureLabel: String = "",
    val currentPosition: CameraPosition = CameraPosition.Back,
    val cameraSettingsSummary: String = "Preparing camera",
    val livePreviewSummary: String = "Idle",
    val lastError: String? = null
)

data class ResolutionOption(
    val width: Int,
    val height: Int,
    val maxFps: Int,
    val highSpeed: Boolean = false
)

data class CaptureFileInfo(
    val name: String,
    val sizeBytes: Long,
    val file: File
)

data class CaptureInfo(
    val name: String,
    val totalBytes: Long,
    val files: List<CaptureFileInfo>
)

private data class ArmedCapture(
    val dir: File,
    val base: String,
    val videoFile: File,
    val intrinsicsFile: File,
    val stateFile: File,
    val encoder: RollingVideoEncoder,
    val encoderSurface: Surface,
    val intrinsicCalibration: FloatArray?
)

class CaptureCameraController(private val context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val transferExecutor = Executors.newSingleThreadExecutor()
    private val transferring = AtomicBoolean(false)
    private val warmingUp = AtomicBoolean(false)
    private val sessionGeneration = AtomicInteger(0)
    private val highSpeedPrearmGeneration = AtomicInteger(0)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    private val characteristicsCache = mutableMapOf<String, CameraCharacteristics>()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var textureView: TextureView? = null
    private var livePreviewStreamer: PhoneLivePreviewStreamer? = null
    private var livePreviewReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var captureSessionArmed = false
    private var currentPosition = CameraPosition.Back

    private var selectedResolution = ResolutionOption(1920, 1080, 30)
    private var previewResolution = ResolutionOption(1280, 720, 30)
    private var targetIso = 2000
    private var targetShutterSeconds = 1.0 / 1000.0
    private var prerollMs = 1000L
    private var cameraLeadUs = 0L
    private var captureLabel = ""

    @Volatile
    private var recording = false

    private var armedCapture: ArmedCapture? = null
    private var lastArmFailureReason: String? = null
    private var activeEncoder: RollingVideoEncoder? = null
    private var captureDir: File? = null
    private var currentBase: String? = null
    private var pendingStopDate: Date? = null
    private var pendingStopLabel: String? = null
    private var intrinsicsWriter: BufferedWriter? = null
    private var activeIntrinsicCalibration: FloatArray? = null
    private var frameIndex = 0
    private var firstSensorTimestampNs: Long? = null

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraIfReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            backgroundHandler?.post {
                closeCaptureSession()
                previewSurface?.release()
                previewSurface = null
            }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            if (recording) {
                appendIntrinsics(result)
            }
        }
    }

    fun start() {
        if (backgroundThread == null) {
            val thread = HandlerThread("capture-camera")
            thread.start()
            backgroundThread = thread
            backgroundHandler = Handler(thread.looper)
        }
        openCameraIfReady()
    }

    fun stop() {
        livePreviewStreamer?.shutdown()
        livePreviewStreamer = null
        updateLivePreviewSummary("Idle")
        backgroundHandler?.post {
            if (recording) {
                stopRecordingInternal()
            }
            closeCamera()
        }
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(800)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
        transferExecutor.shutdownNow()
        toneGenerator.release()
    }

    fun attachPreview(view: TextureView) {
        if (textureView === view && view.surfaceTextureListener === textureListener) {
            return
        }
        textureView = view
        view.surfaceTextureListener = textureListener
        if (view.isAvailable) {
            openCameraIfReady()
        }
    }

    fun startLivePreview(rawRequest: String, stateNotifier: (String) -> Unit) {
        val request = LivePreviewStartRequest.fromJson(rawRequest)
        if (request == null) {
            val state = LivePreviewState(
                active = false,
                message = "Live preview rejected",
                error = "BAD_REQUEST",
            )
            updateLivePreviewSummary(state.summary())
            stateNotifier(state.toJsonString())
            return
        }

        livePreviewStreamer?.shutdown()
        val streamer = PhoneLivePreviewStreamer(
            imageReaderProvider = { livePreviewReader },
            rotationQuarterTurnsProvider = { livePreviewRotationQuarterTurns() },
            stateCallback = { state ->
                updateLivePreviewSummary(state.summary())
                stateNotifier(state.toJsonString())
            }
        )
        livePreviewStreamer = streamer

        val handler = backgroundHandler
        if (handler == null) {
            livePreviewStreamer = null
            val state = LivePreviewState(
                active = false,
                message = "Live preview rejected",
                error = "NO_CAMERA",
            )
            updateLivePreviewSummary(state.summary())
            stateNotifier(state.toJsonString())
            return
        }

        handler.post {
            recreateIdleSession(clearArm = true) { success ->
                if (livePreviewStreamer !== streamer) {
                    return@recreateIdleSession
                }
                if (success) {
                    streamer.start(request)
                    return@recreateIdleSession
                }

                streamer.shutdown()
                livePreviewStreamer = null
                closeLivePreviewReader()
                val state = LivePreviewState(
                    active = false,
                    message = "Live preview rejected",
                    error = "STREAM_SETUP_FAILED",
                )
                updateLivePreviewSummary(state.summary())
                stateNotifier(state.toJsonString())
                if (!recording && cameraDevice != null) {
                    recreateIdleSession(clearArm = true)
                }
            }
        }
    }

    fun stopLivePreview(stateNotifier: ((String) -> Unit)? = null) {
        livePreviewStreamer?.shutdown()
        livePreviewStreamer = null
        backgroundHandler?.post {
            closeLivePreviewReader()
            if (!recording && cameraDevice != null) {
                recreateIdleSession(clearArm = true) {
                    scheduleHighSpeedPrearm(delayMs = 100L)
                }
            }
        }
        val state = LivePreviewState(
            active = false,
            message = "Stopped",
        )
        updateLivePreviewSummary(state.summary())
        stateNotifier?.invoke(state.toJsonString())
    }

    fun switchToFrontCamera() {
        switchCamera(CameraPosition.Front)
    }

    fun switchToBackCamera() {
        switchCamera(CameraPosition.Back)
    }

    fun setCaptureLabelFromTCP(raw: String) {
        captureLabel = sanitizeCaptureLabel(raw)
        _uiState.update { it.copy(captureLabel = captureLabel) }
    }

    fun setPrerollFromTCP(rawMs: Long) {
        val clamped = rawMs.coerceIn(0L, 5000L)
        if (clamped == prerollMs) {
            return
        }
        prerollMs = clamped
        backgroundHandler?.post {
            if (!recording && cameraDevice != null) {
                recreateIdleSession(clearArm = true)
            }
        }
    }

    fun setCameraLeadFromTCP(rawMs: Double) {
        cameraLeadUs = (rawMs.coerceIn(0.0, 5000.0) * 1000.0).toLong()
    }

    fun startRecording(commandReceivedWallNs: Long? = null, completion: (String) -> Unit = {}) {
        val handler = backgroundHandler
        if (handler == null) {
            completion("START_ERR NO_CAMERA_THREAD")
            return
        }
        handler.post { startRecordingInternal(commandReceivedWallNs, completion) }
    }

    fun prepareForRecording(completion: (String) -> Unit = {}) {
        val handler = backgroundHandler
        if (handler == null) {
            completion("PREPARE_ERR NO_CAMERA_THREAD")
            return
        }
        handler.post { prepareForRecordingInternal(completion) }
    }

    fun stopRecording(commandReceivedWallNs: Long? = null, completion: (String) -> Unit = {}) {
        val handler = backgroundHandler
        if (handler == null) {
            completion("STOP_ERR NO_CAMERA_THREAD")
            return
        }
        handler.post { stopRecordingInternal(commandReceivedWallNs, completion) }
    }

    fun transferBusyReason(): String? {
        return when {
            recording -> "RECORDING"
            warmingUp.get() -> "WARMUP"
            transferring.get() -> "TRANSFERRING"
            else -> null
        }
    }

    private fun updateLivePreviewSummary(summary: String) {
        _uiState.update { it.copy(livePreviewSummary = summary) }
    }

    fun buildCameraSettingsJSON(): String {
        val id = cameraId ?: findCameraId(currentPosition)
        val options = if (id != null) availableResolutionOptions(id) else emptyList()
        val current = selectedResolution
        val payload = JSONObject()
            .put("position", if (currentPosition == CameraPosition.Front) "front" else "back")
            .put("resolutions", JSONArray().apply {
                options.forEach { option ->
                    put(
                        JSONObject()
                            .put("width", option.width)
                            .put("height", option.height)
                            .put("fps", option.maxFps)
                            .put("highSpeed", option.highSpeed)
                    )
                }
            })
            .put(
                "current",
                JSONObject()
                    .put("width", current.width)
                    .put("height", current.height)
                    .put("fps", current.maxFps)
                    .put("highSpeed", current.highSpeed)
                    .put("iso", targetIso)
                    .put("shutterSeconds", targetShutterSeconds)
            )
        return payload.toString()
    }

    fun applyRemoteCameraSettings(
        json: String,
        completion: (response: String?, error: String?) -> Unit
    ) {
        val objectValue = try {
            JSONObject(json)
        } catch (_: Exception) {
            completion(null, "BAD_JSON")
            return
        }

        val requiredFields = listOf("width", "height", "iso", "shutterSeconds")
        if (requiredFields.any { !objectValue.has(it) || objectValue.isNull(it) }) {
            completion(null, "MISSING_FIELDS")
            return
        }

        val width = objectValue.optInt("width", -1)
        val height = objectValue.optInt("height", -1)
        val fps = if (objectValue.has("fps") && !objectValue.isNull("fps")) {
            objectValue.optDouble("fps", -1.0)
        } else {
            -1.0
        }
        val iso = objectValue.optDouble("iso", -1.0)
        val shutterSeconds = objectValue.optDouble("shutterSeconds", -1.0)

        if (width <= 0 || height <= 0 || iso <= 0.0 || shutterSeconds <= 0.0) {
            completion(null, "INVALID_VALUES")
            return
        }
        if (objectValue.has("fps") && fps <= 0.0) {
            completion(null, "INVALID_VALUES")
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            completion(null, "NO_CAMERA")
            return
        }

        handler.post {
            if (recording) {
                completion(null, "BUSY_RECORDING")
                return@post
            }
            if (warmingUp.get()) {
                completion(null, "BUSY_WARMUP")
                return@post
            }

            val id = cameraId ?: findCameraId(currentPosition)
            if (id == null) {
                completion(null, "NO_CAMERA")
                return@post
            }

            val option = availableResolutionOptions(id).firstOrNull {
                it.width == width && it.height == height &&
                    (fps <= 0.0 || abs(it.maxFps - fps) < 0.01)
            } ?: availableResolutionOptions(id).firstOrNull {
                it.width == width && it.height == height && fps <= 0.0
            }
            if (option == null) {
                completion(null, "UNSUPPORTED_CAMERA_MODE")
                return@post
            }

            selectedResolution = option
            targetIso = iso.toInt().coerceAtLeast(1)
            targetShutterSeconds = shutterSeconds
            publishSettings()
            cancelHighSpeedPrearm()
            val useHighSpeedSession = selectedResolution.highSpeed
            recreateIdleSession(
                clearArm = true,
                allowArm = true,
                forceArm = useHighSpeedSession
            ) { success ->
                if (success) {
                    completion(buildCameraSettingsJSON(), null)
                    if (!useHighSpeedSession && selectedResolution.highSpeed) {
                        scheduleHighSpeedPrearm(delayMs = 100L)
                    }
                } else {
                    completion(null, "FORMAT_APPLY_FAILED")
                }
            }
        }
    }

    fun buildCaptureListJSON(): String {
        return JSONObject()
            .put("captures", JSONArray().apply {
                listCaptures().forEach { capture ->
                    put(
                        JSONObject()
                            .put("name", capture.name)
                            .put("totalBytes", capture.totalBytes)
                            .put("files", JSONArray().apply {
                                capture.files.forEach { file ->
                                    put(
                                        JSONObject()
                                            .put("name", file.name)
                                            .put("sizeBytes", file.sizeBytes)
                                    )
                                }
                            })
                    )
                }
            })
            .toString()
    }

    fun listCaptures(): List<CaptureInfo> {
        val root = capturesRoot()
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith("pending_") }
            ?: return emptyList()
        return dirs.mapNotNull { dir -> captureInfo(dir.name) }.sortedBy { it.name }
    }

    fun deleteCapture(named: String): Boolean {
        val dir = File(capturesRoot(), named)
        return dir.exists() && dir.deleteRecursively()
    }

    fun deleteAllCaptures(): Boolean {
        val root = capturesRoot()
        return !root.exists() || root.deleteRecursively()
    }

    fun transferCapture(named: String, tcp: TcpController) {
        transferExecutor.execute {
            transferBusyReason()?.let {
                tcp.sendLine("BUSY $it")
                return@execute
            }
            val capture = captureInfo(named)
            if (capture == null) {
                tcp.sendLine("TRANSFER_ERR $named NOT_FOUND")
                return@execute
            }
            setTransferInProgress(true)
            tcp.sendTransfer(
                name = capture.name,
                files = capture.files.map {
                    TcpController.TransferFile(
                        file = it.file,
                        relativePath = "${capture.name}/${it.name}",
                        sizeBytes = it.sizeBytes
                    )
                },
                isAll = false
            ) {
                setTransferInProgress(false)
            }
        }
    }

    fun transferAllCaptures(tcp: TcpController) {
        transferExecutor.execute {
            transferBusyReason()?.let {
                tcp.sendLine("BUSY $it")
                return@execute
            }
            val files = listCaptures().flatMap { capture ->
                capture.files.map {
                    TcpController.TransferFile(
                        file = it.file,
                        relativePath = "${capture.name}/${it.name}",
                        sizeBytes = it.sizeBytes
                    )
                }
            }
            if (files.isEmpty()) {
                tcp.sendLine("TRANSFER_ERR ALL EMPTY")
                return@execute
            }
            setTransferInProgress(true)
            tcp.sendTransfer(name = "ALL", files = files, isAll = true) {
                setTransferInProgress(false)
            }
        }
    }

    private fun switchCamera(position: CameraPosition) {
        backgroundHandler?.post {
            if (recording || currentPosition == position) return@post
            currentPosition = position
            _uiState.update { it.copy(currentPosition = position) }
            closeCamera()
            openCameraInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraIfReady() {
        backgroundHandler?.post { openCameraInternal() }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val view = textureView ?: return
        if (!view.isAvailable || cameraDevice != null) {
            if (cameraDevice != null && !recording) {
                recreateIdleSession {
                    scheduleHighSpeedPrearm(delayMs = 100L)
                }
            }
            return
        }

        val id = findCameraId(currentPosition)
        if (id == null) {
            setError("No ${currentPosition.name.lowercase(Locale.US)} camera found")
            return
        }

        cameraId = id
        selectedResolution = chooseResolution(
            id,
            selectedResolution.width,
            selectedResolution.height,
            selectedResolution.maxFps
        )
        previewResolution = choosePreviewResolution(id)
        publishSettings()

        try {
            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        recreateIdleSession(clearArm = true) {
                            scheduleHighSpeedPrearm(delayMs = 100L)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        setError("Camera error $error")
                    }
                },
                backgroundHandler
            )
        } catch (error: CameraAccessException) {
            setError(error.message ?: "Camera access failed")
        }
    }

    private fun recreateIdleSession(
        clearArm: Boolean = false,
        allowArm: Boolean = true,
        forceArm: Boolean = false,
        onConfigured: ((Boolean) -> Unit)? = null
    ) {
        if (recording) {
            onConfigured?.invoke(false)
            return
        }
        val device = cameraDevice ?: run {
            onConfigured?.invoke(false)
            return
        }

        val generation = sessionGeneration.incrementAndGet()
        closeCaptureSession()
        lastArmFailureReason = null
        if (clearArm) {
            discardArmedCapture()
        }
        val surface = preparePreviewSurface() ?: run {
            onConfigured?.invoke(false)
            return
        }
        val livePreviewSurface = if (livePreviewStreamer != null) {
            prepareLivePreviewSurface() ?: run {
                onConfigured?.invoke(false)
                return
            }
        } else {
            closeLivePreviewReader()
            null
        }
        val shouldPrepareArmedCapture = allowArm && (!selectedResolution.highSpeed || forceArm)
        val armed = if (shouldPrepareArmedCapture) prepareArmedCapture() else null
        if (shouldPrepareArmedCapture && armed == null) {
            if (lastArmFailureReason == null) {
                lastArmFailureReason = "ENCODER_SETUP_FAILED"
            }
            onConfigured?.invoke(false)
            return
        }
        val template = if (armed != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val useHighSpeedSession = armed != null && livePreviewSurface == null && isSelectedHighSpeedMode()
        val includePreviewSurface = true
        val outputs = buildList {
            if (includePreviewSurface) {
                add(surface)
            }
            livePreviewSurface?.let { add(it) }
            armed?.let { add(it.encoderSurface) }
        }

        try {
            val stateCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != sessionGeneration.get()) {
                            session.close()
                            return
                        }
                        if (cameraDevice == null || recording) {
                            session.close()
                            onConfigured?.invoke(false)
                            return
                        }
                        captureSession = session
                        captureSessionArmed = armed != null
                        try {
                            val request = device.createCaptureRequest(template).apply {
                                if (includePreviewSurface) {
                                    addTarget(surface)
                                }
                                livePreviewSurface?.let { addTarget(it) }
                                armed?.let { addTarget(it.encoderSurface) }
                                applyCameraRequestSettings(this, useHighSpeedSession = useHighSpeedSession)
                            }.build()
                            if (useHighSpeedSession) {
                                val highSpeedSession = session as? CameraConstrainedHighSpeedCaptureSession
                                    ?: throw IllegalStateException("High-speed session was not created")
                                highSpeedSession.setRepeatingBurst(
                                    highSpeedSession.createHighSpeedRequestList(request),
                                    captureCallback,
                                    backgroundHandler
                                )
                            } else {
                                session.setRepeatingRequest(request, captureCallback, backgroundHandler)
                            }
                            setError(null)
                            onConfigured?.invoke(true)
                        } catch (error: Exception) {
                            session.close()
                            if (generation != sessionGeneration.get()) {
                                return
                            }
                            captureSession = null
                            captureSessionArmed = false
                            if (armed != null && allowArm && !forceArm) {
                                discardArmedCapture()
                                recreateIdleSession(allowArm = false, onConfigured = onConfigured)
                                return
                            }
                            lastArmFailureReason = formatProtocolError(error.message ?: "CAPTURE_SESSION_START_FAILED")
                            setError(error.message ?: "Preview failed")
                            onConfigured?.invoke(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (generation != sessionGeneration.get()) {
                            session.close()
                            return
                        }
                        captureSessionArmed = false
                        if (armed != null && allowArm && !forceArm) {
                            discardArmedCapture()
                            recreateIdleSession(allowArm = false, onConfigured = onConfigured)
                            return
                        }
                        lastArmFailureReason = "CAPTURE_SESSION_CONFIG_FAILED"
                        setError("Preview configuration failed")
                        onConfigured?.invoke(false)
                    }
                }

            if (useHighSpeedSession) {
                @Suppress("DEPRECATION")
                device.createConstrainedHighSpeedCaptureSession(outputs, stateCallback, backgroundHandler)
            } else {
                device.createCaptureSession(outputs, stateCallback, backgroundHandler)
            }
        } catch (error: CameraAccessException) {
            captureSessionArmed = false
            if (armed != null && allowArm && !forceArm) {
                discardArmedCapture()
                recreateIdleSession(allowArm = false, onConfigured = onConfigured)
                return
            }
            lastArmFailureReason = formatProtocolError(error.message ?: "CAPTURE_SESSION_CREATE_FAILED")
            setError(error.message ?: "Preview configuration failed")
            onConfigured?.invoke(false)
        }
    }

    private fun startRecordingInternal(commandReceivedWallNs: Long? = null, completion: (String) -> Unit = {}) {
        val requestBeginNs = SystemClock.elapsedRealtimeNanos()
        if (recording) {
            completion("START_OK ALREADY_RECORDING")
            return
        }
        val id = cameraId ?: run {
            completion("START_ERR NO_CAMERA")
            return
        }
        cancelHighSpeedPrearm()
        warmingUp.set(true)
        ensureIdleSessionArmed { armedReady ->
            if (!armedReady || recording) {
                warmingUp.set(false)
                completion(if (recording) "START_OK ALREADY_RECORDING" else "START_ERR ENCODER_NOT_ARMED")
                return@ensureIdleSessionArmed
            }

            val armed = armedCapture ?: run {
                warmingUp.set(false)
                setError("Encoder not ready")
                completion("START_ERR ENCODER_NOT_READY")
                return@ensureIdleSessionArmed
            }

            frameIndex = 0
            firstSensorTimestampNs = null
            pendingStopDate = null
            pendingStopLabel = null
            captureDir = armed.dir
            currentBase = armed.base
            activeEncoder = armed.encoder
            armedCapture = null

            try {
                openIntrinsicsCsv(armed.intrinsicsFile)
                writeStateJson(armed.stateFile, id, captureLabel)
                activeIntrinsicCalibration = armed.intrinsicCalibration
                val requestedStartUs = activeEncoder?.beginSegment(commandReceivedWallNs, cameraLeadUs)
                val requestMarkedNs = SystemClock.elapsedRealtimeNanos()
                warmingUp.set(false)
                recording = true
                _uiState.update { it.copy(isRecording = true, lastError = null) }
                playTone(ToneGenerator.TONE_PROP_ACK)
                completion(
                    "START_OK ROLLING_BUFFER " +
                        "phone_start_begin_ns=$requestBeginNs " +
                        "phone_start_marked_ns=$requestMarkedNs " +
                        "requested_start_us=${requestedStartUs ?: -1L} " +
                        "camera_lead_ms=${cameraLeadUs / 1000.0}"
                )
            } catch (error: Exception) {
                warmingUp.set(false)
                setError(error.message ?: "Recording failed")
                cleanupFailedRecording()
                recreateIdleSession(clearArm = true)
                completion("START_ERR ${formatProtocolError(error.message ?: "RECORDING_FAILED")}")
            }
        }
    }

    private fun prepareForRecordingInternal(completion: (String) -> Unit = {}) {
        if (recording) {
            completion("PREPARE_OK RECORDING")
            return
        }
        waitForPreparedEncoder(rearmIfNeeded = true) { ready, reason ->
            completion(
                if (ready) {
                    "PREPARE_OK READY preroll_ms=$prerollMs camera_lead_ms=${cameraLeadUs / 1000.0}"
                } else {
                    "PREPARE_ERR $reason"
                }
            )
        }
    }

    private fun stopRecordingInternal(commandReceivedWallNs: Long? = null, completion: (String) -> Unit = {}) {
        val requestBeginNs = SystemClock.elapsedRealtimeNanos()
        if (!recording) {
            completion("STOP_OK NOT_RECORDING")
            return
        }
        cancelHighSpeedPrearm()
        pendingStopDate = Date()
        pendingStopLabel = captureLabel
        recording = false
        _uiState.update { it.copy(isRecording = false) }
        playTone(ToneGenerator.TONE_PROP_BEEP)

        val encoder = activeEncoder
        activeEncoder = null
        val videoFile = captureDir?.let { dir ->
            currentBase?.let { base -> File(dir, "$base.mp4") }
        }
        var stopError: String? = null
        var requestedEndUs: Long? = null
        var segmentMarkedNs: Long? = null
        var muxDoneNs: Long? = null
        try {
            requestedEndUs = encoder?.endSegment(commandReceivedWallNs)
            segmentMarkedNs = SystemClock.elapsedRealtimeNanos()
            if (encoder != null && videoFile != null) {
                val segment = encoder.finishSegment(videoFile)
                muxDoneNs = SystemClock.elapsedRealtimeNanos()
                writeSegmentJson(segment, File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.segment.json"))
            } else {
                stopError = "Encoder was not active"
            }
        } catch (error: Exception) {
            stopError = error.message ?: "Recording could not be finalized"
            setError(stopError)
        }

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (_: Exception) {
            // The session may already be closed by the camera service.
        }

        try {
            encoder?.stop()
        } catch (_: Exception) {
        }

        closeIntrinsicsCsv()
        activeIntrinsicCalibration = null
        if (stopError == null) {
            finalizeCaptureNames()
            completion(
                "STOP_OK MEDIACODEC_MUXED " +
                    "phone_stop_begin_ns=$requestBeginNs " +
                    "phone_stop_marked_ns=${segmentMarkedNs ?: -1L} " +
                    "phone_stop_mux_done_ns=${muxDoneNs ?: -1L} " +
                    "requested_end_us=${requestedEndUs ?: -1L}"
            )
        } else {
            captureDir?.deleteRecursively()
            completion("STOP_ERR ${formatProtocolError(stopError)}")
        }
        captureDir = null
        currentBase = null
        pendingStopDate = null
        pendingStopLabel = null
        recreateIdleSession(clearArm = true, allowArm = !selectedResolution.highSpeed) { success ->
            if (success && selectedResolution.highSpeed) {
                scheduleHighSpeedPrearm(delayMs = 100L)
            }
            if (!success) {
                setError("Preview rearm failed after stop")
            }
        }
    }

    private fun cleanupFailedRecording() {
        warmingUp.set(false)
        recording = false
        _uiState.update { it.copy(isRecording = false) }
        try {
            activeEncoder?.stop()
        } catch (_: Exception) {
        }
        activeEncoder = null
        closeIntrinsicsCsv()
        activeIntrinsicCalibration = null
        captureDir?.deleteRecursively()
        captureDir = null
        currentBase = null
    }

    private fun closeCamera() {
        cancelHighSpeedPrearm()
        closeCaptureSession()
        cameraDevice?.close()
        cameraDevice = null
        cameraId = null
        discardArmedCapture()
        activeEncoder?.stop()
        activeEncoder = null
        closeLivePreviewReader()
        previewSurface?.release()
        previewSurface = null
        activeIntrinsicCalibration = null
    }

    private fun closeCaptureSession() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null
        captureSessionArmed = false
    }

    private fun preparePreviewSurface(): Surface? {
        val texture = textureView?.surfaceTexture ?: return null
        val size = if (selectedResolution.highSpeed) selectedResolution else previewResolution
        texture.setDefaultBufferSize(size.width, size.height)
        previewSurface?.release()
        return Surface(texture).also { previewSurface = it }
    }

    private fun prepareLivePreviewSurface(): Surface? {
        if (livePreviewStreamer == null) {
            closeLivePreviewReader()
            return null
        }

        val id = cameraId ?: return null
        val size = chooseLivePreviewSize(id) ?: return null
        val existingReader = livePreviewReader
        if (existingReader != null && existingReader.width == size.width && existingReader.height == size.height) {
            return existingReader.surface
        }

        closeLivePreviewReader()
        return ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            .also { livePreviewReader = it }
            .surface
    }

    private fun closeLivePreviewReader() {
        livePreviewReader?.close()
        livePreviewReader = null
    }

    private fun createRollingVideoEncoder(): RollingVideoEncoder {
        val fps = selectedResolution.maxFps.coerceAtLeast(1)
        val bitRate = (selectedResolution.width.toLong() * selectedResolution.height * fps * 0.20)
            .toLong()
            .coerceIn(16_000_000L, 160_000_000L)
            .toInt()
        return RollingVideoEncoder(
            width = selectedResolution.width,
            height = selectedResolution.height,
            fps = fps,
            bitRate = bitRate,
            orientationDegrees = orientationHint(),
            prerollMs = prerollMs,
            tempDir = appContext.cacheDir,
            useTimestampedInput = !selectedResolution.highSpeed
        )
    }

    private fun applyCameraRequestSettings(
        builder: CaptureRequest.Builder,
        useHighSpeedSession: Boolean = false,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        bestFpsRange(useHighSpeedSession)?.let {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }

        if (useHighSpeedSession) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            return
        }

        val id = cameraId ?: return
        if (!supportsManualSensor(id)) return

        val characteristics = cameraCharacteristics(id)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureNs = (targetShutterSeconds * 1_000_000_000.0).toLong()
        val clampedExposureNs = exposureRange?.let { exposureNs.coerceIn(it.lower, it.upper) } ?: exposureNs
        val clampedIso = isoRange?.let { targetIso.coerceIn(it.lower, it.upper) } ?: targetIso

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)
    }

    private fun appendIntrinsics(result: CaptureResult) {
        val writer = intrinsicsWriter ?: return
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
        val startNs = firstSensorTimestampNs ?: timestampNs.also { firstSensorTimestampNs = it }
        val timestampSeconds = (timestampNs - startNs) / 1_000_000_000.0

        val k = activeIntrinsicCalibration
        val fx = k.valueAt(0)
        val fy = k.valueAt(1)
        val cx = k.valueAt(2)
        val cy = k.valueAt(3)
        val skew = k.valueAt(4)
        val lensPosition = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        val exposureSeconds = (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1_000_000_000.0
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f
        } else {
            1f
        }

        frameIndex += 1
        writer.write(
            String.format(
                Locale.US,
                "%d,%.9f,%.7f,%.7f,%.7f,%.7f,%.7f,%.7f,%.7f,%.7f,%.7f,%.4f,%.9f,%.2f,%.3f\n",
                frameIndex,
                timestampSeconds,
                fx,
                skew,
                cx,
                0f,
                fy,
                cy,
                0f,
                0f,
                1f,
                lensPosition,
                exposureSeconds,
                iso.toFloat(),
                zoom
            )
        )
        if (frameIndex % 30 == 0) {
            writer.flush()
        }
    }

    private fun FloatArray?.valueAt(index: Int): Float {
        return if (this != null && index in indices) this[index] else 0f
    }

    private fun openIntrinsicsCsv(file: File) {
        intrinsicsWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).also {
            it.write("frame_idx,timestamp_seconds,k00,k01,k02,k10,k11,k12,k20,k21,k22,lens_position,exposure_seconds,iso,zoom\n")
        }
    }

    private fun closeIntrinsicsCsv() {
        try {
            intrinsicsWriter?.flush()
            intrinsicsWriter?.close()
        } catch (_: Exception) {
        }
        intrinsicsWriter = null
    }

    private fun writeStateJson(file: File, id: String, label: String) {
        val characteristics = cameraCharacteristics(id)
        val payload = JSONObject()
            .put("timestamp_unix", System.currentTimeMillis() / 1000.0)
            .put("capture_label", label)
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("camera_id", id)
            .put("active_width", selectedResolution.width)
            .put("active_height", selectedResolution.height)
            .put("desired_fps", selectedResolution.maxFps)
            .put("active_min_frame_duration", 1.0 / selectedResolution.maxFps.coerceAtLeast(1))
            .put("active_max_frame_duration", 1.0 / selectedResolution.maxFps.coerceAtLeast(1))
            .put("target_shutter_seconds", targetShutterSeconds)
            .put("target_iso", targetIso)
            .put("recording_backend", "mediacodec_rolling_buffer")
            .put("preroll_ms", prerollMs)
            .put("manual_sensor_supported", supportsManualSensor(id))
            .put("disable_video_stabilization", true)
            .put("orientation", "portrait")
            .put("platform", "android")

        characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { k ->
            payload.put("intrinsic_calibration", JSONArray(k.toList()))
        }
        file.writeText(payload.toString(2), Charsets.UTF_8)
    }

    private fun writeSegmentJson(result: RollingVideoSegmentResult, file: File) {
        val payload = JSONObject()
            .put("backend", "mediacodec_rolling_buffer")
            .put("preroll_ms", result.prerollMs)
            .put("camera_lead_ms", result.cameraLeadUs / 1000.0)
            .put("sample_count", result.sampleCount)
            .put("frame_selection_policy", result.frameSelectionPolicy)
            .put("all_intra", result.allIntra)
            .put("requested_start_us", result.requestedStartUs)
            .put("requested_end_us", result.requestedEndUs)
            .put("muxed_start_us", result.muxedStartUs)
            .put("muxed_end_us", result.muxedEndUs)
            .put("first_presentation_us", result.firstPresentationUs)
            .put("last_presentation_us", result.lastPresentationUs)
            .put("start_offset_error_us", result.muxedStartUs - result.requestedStartUs)
            .put("end_offset_error_us", result.muxedEndUs - result.requestedEndUs)
            .put("timestamp_source", result.timestampSource)
        file.writeText(payload.toString(2), Charsets.UTF_8)
    }

    private fun makeNewCaptureFolder(): Pair<File, String> {
        val base = "pending_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val dir = File(capturesRoot(), base)
        dir.mkdirs()
        return dir to base
    }

    private fun finalizeCaptureNames() {
        val dir = captureDir ?: return
        val oldBase = currentBase ?: return
        val stopDate = pendingStopDate ?: Date()
        val stopLabel = pendingStopLabel ?: captureLabel
        val parent = dir.parentFile ?: return

        var newBase = makeCaptureBase(stopLabel, stopDate)
        var newDir = File(parent, newBase)
        if (newDir.exists()) {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(4)
            newBase = "${newBase}_$suffix"
            newDir = File(parent, newBase)
        }

        if (!dir.renameTo(newDir)) {
            setError("Failed to finalize capture ${dir.name}")
            return
        }

        listOf("mp4", "intrinsics.csv", "state.json", "segment.json").forEach { suffix ->
            val oldFile = File(newDir, "$oldBase.$suffix")
            if (oldFile.exists()) {
                oldFile.renameTo(File(newDir, "$newBase.$suffix"))
            }
        }

        captureDir = newDir
        currentBase = newBase
        pendingStopDate = null
        pendingStopLabel = null
    }

    private fun makeCaptureBase(label: String, date: Date): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
        val device = cameraDeviceId()
        return if (label.isBlank()) {
            "${stamp}_kinematiphone_$device"
        } else {
            "${label}_${stamp}_kinematiphone_$device"
        }
    }

    private fun captureInfo(name: String): CaptureInfo? {
        val dir = File(capturesRoot(), name)
        if (!dir.exists() || !dir.isDirectory || dir.name.startsWith("pending_")) return null
        val files = dir.listFiles { file -> file.isFile }?.map {
            CaptureFileInfo(it.name, it.length(), it)
        }?.sortedBy { it.name } ?: return null
        return CaptureInfo(name = dir.name, totalBytes = files.sumOf { it.sizeBytes }, files = files)
    }

    private fun capturesRoot(): File {
        return File(appContext.filesDir, "Captures").also { it.mkdirs() }
    }

    private fun setTransferInProgress(value: Boolean) {
        transferring.set(value)
        _uiState.update { it.copy(isTransferring = value) }
    }

    private fun setError(message: String?) {
        _uiState.update { it.copy(lastError = message) }
    }

    private fun publishSettings() {
        val fpsText = selectedResolution.maxFps.toString()
        val isoText = targetIso.toString()
        val shutterText = formattedShutter(targetShutterSeconds)
        val summary = "${selectedResolution.width}x${selectedResolution.height} @ $fpsText fps | ISO $isoText | $shutterText"
        _uiState.update {
            it.copy(
                currentPosition = currentPosition,
                cameraSettingsSummary = summary
            )
        }
    }

    private fun formattedShutter(seconds: Double): String {
        if (seconds <= 0.0) return "0s"
        val reciprocal = 1.0 / seconds
        return if (reciprocal >= 1.0) {
            "1/${reciprocal.roundToInt()} s"
        } else {
            val number = String.format(Locale.US, "%.4f", seconds).trimEnd('0').trimEnd('.')
            "${number}s"
        }
    }

    private fun chooseResolution(
        id: String,
        desiredWidth: Int,
        desiredHeight: Int,
        desiredFps: Int = 0
    ): ResolutionOption {
        val options = availableResolutionOptions(id)
        return options.firstOrNull {
            it.width == desiredWidth && it.height == desiredHeight &&
                (desiredFps <= 0 || it.maxFps == desiredFps)
        }
            ?: options.firstOrNull { it.width == desiredWidth && it.height == desiredHeight }
            ?: options.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: options.firstOrNull { it.width <= 1920 && it.height <= 1080 }
            ?: options.firstOrNull()
            ?: ResolutionOption(1280, 720, 30)
    }

    private fun choosePreviewResolution(id: String): ResolutionOption {
        val options = availableResolutionOptions(id)
        return options.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: options.firstOrNull { it.width <= 1280 && it.height <= 720 }
            ?: options.lastOrNull()
            ?: ResolutionOption(1280, 720, 30)
    }

    private fun chooseLivePreviewSize(id: String): Size? {
        val map = cameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?: return null
        if (sizes.isEmpty()) {
            return null
        }

        sizes.firstOrNull { it.width == selectedResolution.width && it.height == selectedResolution.height }
            ?.let { return it }

        val targetAspect = selectedResolution.width.toDouble() / selectedResolution.height.toDouble()
        val targetArea = selectedResolution.width.toLong() * selectedResolution.height.toLong()
        return sizes.minByOrNull { size ->
            val aspectError = abs((size.width.toDouble() / size.height.toDouble()) - targetAspect)
            val areaError = abs((size.width.toLong() * size.height.toLong()) - targetArea).toDouble() /
                maxOf(1.0, targetArea.toDouble())
            aspectError * 1000.0 + areaError
        }
    }

    private fun availableResolutionOptions(id: String): List<ResolutionOption> {
        val characteristics = cameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val maxAeFps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxOfOrNull { it.upper }
            ?: 30
        val sizes = map.getOutputSizes(MediaCodec::class.java)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: emptyArray()

        val optionsByMode = linkedMapOf<String, ResolutionOption>()
        val normalFpsValues = selectableNormalFpsValues(
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: emptyArray(),
            maxAeFps,
        )
        sizes.distinctBy { "${it.width}x${it.height}" }.forEach { size ->
            normalFpsValues.forEach { fps ->
                val option = ResolutionOption(
                    width = size.width,
                    height = size.height,
                    maxFps = fps,
                    highSpeed = false
                )
                optionsByMode["${option.width}x${option.height}@${option.maxFps}"] = option
            }
        }

        highSpeedResolutionOptions(characteristics, map).forEach { option ->
            val key = "${option.width}x${option.height}@${option.maxFps}"
            if (!optionsByMode.containsKey(key)) {
                optionsByMode[key] = option
            }
        }

        return optionsByMode.values
            .sortedWith(
                compareByDescending<ResolutionOption> { it.width.toLong() * it.height.toLong() }
                    .thenByDescending { it.width }
                    .thenByDescending { it.height }
                    .thenByDescending { it.maxFps }
                    .thenBy { it.highSpeed }
            )
    }

    private fun highSpeedResolutionOptions(
        characteristics: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap
    ): List<ResolutionOption> {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return emptyList()
        if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
            return emptyList()
        }

        val sizes = try {
            map.highSpeedVideoSizes
        } catch (_: Exception) {
            emptyArray()
        }
        return sizes.flatMap { size ->
            val ranges = try {
                map.getHighSpeedVideoFpsRangesFor(size)
            } catch (_: Exception) {
                emptyArray()
            }
            ranges.flatMap { range ->
                selectableHighSpeedFpsValues(range).map { fps ->
                    ResolutionOption(
                        width = size.width,
                        height = size.height,
                        maxFps = fps,
                        highSpeed = true
                    )
                }
            }
        }
    }

    private fun selectableHighSpeedFpsValues(range: Range<Int>): List<Int> {
        val commonValues = listOf(60, 120, 240)
        return buildSet {
            add(range.upper)
            commonValues.forEach { fps ->
                if (fps in range.lower..range.upper) {
                    add(fps)
                }
            }
        }.filter { it > 30 }.sorted()
    }

    private fun selectableNormalFpsValues(ranges: Array<Range<Int>>, fallback: Int): List<Int> {
        val commonValues = listOf(24, 25, 30, 50, 60)
        val values = buildSet {
            ranges.forEach { range ->
                commonValues.forEach { fps ->
                    if (fps in range.lower..range.upper) {
                        add(fps)
                    }
                }
                if (range.lower == range.upper && range.upper > 0) {
                    add(range.upper)
                }
            }
            if (isEmpty() && fallback > 0) {
                add(fallback)
            }
        }
        return values.filter { it > 0 }.sorted()
    }

    private fun highSpeedFpsRangesForSelectedSize(): Array<Range<Int>> {
        val id = cameraId ?: return emptyArray()
        if (!selectedResolution.highSpeed) {
            return emptyArray()
        }
        val map = cameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyArray()
        val size = Size(selectedResolution.width, selectedResolution.height)
        return try {
            map.getHighSpeedVideoFpsRangesFor(size)
        } catch (_: Exception) {
            emptyArray()
        }
    }

    private fun isSelectedHighSpeedMode(): Boolean {
        return selectedResolution.highSpeed && highSpeedFpsRangesForSelectedSize().isNotEmpty()
    }

    private fun normalAeFpsRanges(): Array<Range<Int>> {
        val id = cameraId ?: return emptyArray()
        return cameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
    }

    private fun preferredFpsRange(ranges: Array<Range<Int>>): Range<Int>? {
        val target = selectedResolution.maxFps
        val matchingRanges = ranges.filter { target in it.lower..it.upper }
        return matchingRanges.firstOrNull { it.lower == target && it.upper == target }
            ?: matchingRanges.minWithOrNull(
                compareBy<Range<Int>> { it.upper - it.lower }
                    .thenBy { abs(it.upper - target) + abs(it.lower - target) }
                    .thenBy { it.upper }
            )
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun maxFpsForSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        fallback: Int
    ): Int {
        @Suppress("UNUSED_VARIABLE")
        val ignored = map to size
        return fallback
    }

    private fun bestFpsRange(useHighSpeedSession: Boolean): Range<Int>? {
        val highSpeedRanges = if (useHighSpeedSession) {
            highSpeedFpsRangesForSelectedSize()
        } else {
            emptyArray()
        }
        if (useHighSpeedSession && highSpeedRanges.isNotEmpty()) {
            return preferredFpsRange(highSpeedRanges)
        }
        return preferredFpsRange(normalAeFpsRanges())
    }

    private fun supportsManualSensor(id: String): Boolean {
        val capabilities = cameraCharacteristics(id)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }

    private fun findCameraId(position: CameraPosition): String? {
        val desiredFacing = if (position == CameraPosition.Front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == desiredFacing
        }
    }

    private fun orientationHint(): Int {
        val id = cameraId ?: return 90
        val characteristics = cameraCharacteristics(id)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return if (currentPosition == CameraPosition.Front) {
            (360 - sensorOrientation) % 360
        } else {
            sensorOrientation
        }
    }

    private fun livePreviewRotationQuarterTurns(): Int {
        return ((orientationHint() / 90) % 4 + 4) % 4
    }

    private fun cameraDeviceId(): String {
        val prefs = appContext.getSharedPreferences("capture_bridge", Context.MODE_PRIVATE)
        prefs.getString("camera_id", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US).take(8)
        prefs.edit().putString("camera_id", id).apply()
        return id
    }

    private fun playTone(tone: Int) {
        try {
            toneGenerator.startTone(tone, 120)
        } catch (_: Exception) {
        }
    }

    private fun ensureIdleSessionArmed(onReady: (Boolean) -> Unit) {
        if (captureSessionArmed && armedCapture != null) {
            onReady(true)
            return
        }
        recreateIdleSession(clearArm = true, forceArm = true, onConfigured = onReady)
    }

    private fun waitForPreparedEncoder(
        rearmIfNeeded: Boolean,
        onReady: (Boolean, String) -> Unit
    ) {
        ensureIdleSessionArmed { armedReady ->
            if (!armedReady || armedCapture == null) {
                onReady(false, armFailureReason())
                return@ensureIdleSessionArmed
            }
            if (waitForRollingEncoderReady(armedCapture, timeoutMs = 2500L)) {
                onReady(true, "READY")
                return@ensureIdleSessionArmed
            }
            if (!rearmIfNeeded) {
                onReady(false, "ENCODER_NOT_READY")
                return@ensureIdleSessionArmed
            }
            recreateIdleSession(clearArm = true, forceArm = true) { rearmed ->
                val ready = rearmed && waitForRollingEncoderReady(armedCapture, timeoutMs = 2500L)
                onReady(ready, if (ready) "READY" else armFailureReason())
            }
        }
    }

    private fun armFailureReason(): String {
        return when {
            lastArmFailureReason != null -> lastArmFailureReason ?: "ENCODER_ARM_FAILED"
            cameraDevice == null -> "NO_CAMERA_OPEN"
            textureView?.surfaceTexture == null -> "NO_PREVIEW_SURFACE"
            armedCapture == null -> "ENCODER_NOT_ARMED"
            !captureSessionArmed -> "CAPTURE_SESSION_NOT_ARMED"
            else -> "ENCODER_NOT_READY"
        }
    }

    private fun waitForRollingEncoderReady(armed: ArmedCapture?, timeoutMs: Long = 1200L): Boolean {
        val encoder = armed?.encoder ?: return false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (encoder.isReady()) {
                return true
            }
            Thread.sleep(20L)
        }
        return encoder.isReady()
    }

    private fun cancelHighSpeedPrearm() {
        highSpeedPrearmGeneration.incrementAndGet()
    }

    private fun scheduleHighSpeedPrearm(delayMs: Long = 900L) {
        val handler = backgroundHandler ?: return
        val generation = highSpeedPrearmGeneration.incrementAndGet()
        handler.postDelayed({
            if (generation != highSpeedPrearmGeneration.get()) {
                return@postDelayed
            }
            if (!selectedResolution.highSpeed || recording || warmingUp.get() || livePreviewStreamer != null) {
                return@postDelayed
            }
            recreateIdleSession(clearArm = true, forceArm = true) { success ->
                if (!success && generation == highSpeedPrearmGeneration.get()) {
                    recreateIdleSession(clearArm = true, allowArm = false)
                }
            }
        }, delayMs)
    }

    private fun prepareArmedCapture(): ArmedCapture? {
        armedCapture?.let { return it }
        val id = cameraId ?: return null
        val (dir, base) = makeNewCaptureFolder()
        val videoFile = File(dir, "$base.mp4")
        val intrinsicsFile = File(dir, "$base.intrinsics.csv")
        val stateFile = File(dir, "$base.state.json")

        val encoder = try {
            createRollingVideoEncoder()
        } catch (error: Exception) {
            dir.deleteRecursively()
            lastArmFailureReason = formatProtocolError(error.message ?: "ENCODER_SETUP_FAILED")
            setError(error.message ?: "Encoder setup failed")
            return null
        }
        val encoderSurface = try {
            encoder.start()
        } catch (error: Exception) {
            encoder.stop()
            dir.deleteRecursively()
            lastArmFailureReason = formatProtocolError(error.message ?: "ENCODER_START_FAILED")
            setError(error.message ?: "Encoder start failed")
            return null
        }

        return ArmedCapture(
            dir = dir,
            base = base,
            videoFile = videoFile,
            intrinsicsFile = intrinsicsFile,
            stateFile = stateFile,
            encoder = encoder,
            encoderSurface = encoderSurface,
            intrinsicCalibration = cameraCharacteristics(id).get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        ).also { armedCapture = it }
    }

    private fun discardArmedCapture(deleteFiles: Boolean = true) {
        val armed = armedCapture ?: return
        armedCapture = null
        try {
            armed.encoder.stop()
        } catch (_: Exception) {
        }
        if (deleteFiles) {
            armed.dir.deleteRecursively()
        }
    }

    private fun cameraCharacteristics(id: String): CameraCharacteristics {
        synchronized(characteristicsCache) {
            return characteristicsCache.getOrPut(id) {
                cameraManager.getCameraCharacteristics(id)
            }
        }
    }
}
