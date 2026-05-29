package com.marksimonlehner.capturebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

private const val LIVE_PREVIEW_DEFAULT_PORT = 6101
private const val LIVE_PREVIEW_DEFAULT_FPS = 0
private const val LIVE_PREVIEW_DEFAULT_QUALITY = 70
private const val LIVE_PREVIEW_DEFAULT_MAX_DIMENSION = 0
private const val LIVE_PREVIEW_MAX_PACKET_PAYLOAD = 1200
private const val LIVE_PREVIEW_HEADER_SIZE = 30
private val LIVE_PREVIEW_MAGIC = byteArrayOf(
    'F'.code.toByte(),
    'L'.code.toByte(),
    '3'.code.toByte(),
    'D'.code.toByte()
)
private const val LIVE_PREVIEW_VERSION: Byte = 1

data class LivePreviewStartRequest(
    val host: String,
    val port: Int,
    val maxFps: Int,
    val jpegQuality: Int,
    val maxDimension: Int,
) {
    companion object {
        fun fromJson(json: String): LivePreviewStartRequest? {
            val objectValue = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }

            val host = objectValue.optString("host").trim()
            val port = objectValue.optInt("port", LIVE_PREVIEW_DEFAULT_PORT)
            val maxFps = objectValue.optInt("maxFps", LIVE_PREVIEW_DEFAULT_FPS)
            val jpegQuality = objectValue.optInt("jpegQuality", LIVE_PREVIEW_DEFAULT_QUALITY)
            val maxDimension = objectValue.optInt("maxDimension", LIVE_PREVIEW_DEFAULT_MAX_DIMENSION)

            if (host.isBlank() || port !in 1..65535 || maxFps < 0 || maxDimension < 0) {
                return null
            }

            return LivePreviewStartRequest(
                host = host,
                port = port,
                maxFps = maxFps.coerceAtLeast(0),
                jpegQuality = jpegQuality.coerceIn(20, 95),
                maxDimension = maxDimension.coerceAtLeast(0),
            )
        }
    }
}

data class LivePreviewState(
    val active: Boolean,
    val message: String,
    val host: String? = null,
    val port: Int? = null,
    val error: String? = null,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("active", active)
            .put("message", message)
            .put("host", host)
            .put("port", port)
            .put("error", error)
            .toString()
    }

    fun summary(): String {
        return when {
            active && host != null && port != null -> "$message -> $host:$port"
            error != null -> "$message ($error)"
            else -> message
        }
    }
}

private data class CapturedPreviewFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val rotationQuarterTurns: Int,
)

class PhoneLivePreviewStreamer(
    private val imageReaderProvider: () -> ImageReader?,
    private val rotationQuarterTurnsProvider: () -> Int,
    private val stateCallback: (LivePreviewState) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val nextFrameId = AtomicInteger(1)

    fun start(request: LivePreviewStartRequest) {
        stopInternal()
        val activeGeneration = generation.incrementAndGet()
        running.set(true)
        stateCallback(
            LivePreviewState(
                active = true,
                message = "Starting",
                host = request.host,
                port = request.port,
            )
        )
        worker.execute {
            runLoop(request, activeGeneration)
        }
    }

    fun shutdown() {
        stopInternal()
        worker.shutdownNow()
    }

    private fun stopInternal() {
        running.set(false)
        generation.incrementAndGet()
    }

    private fun runLoop(request: LivePreviewStartRequest, activeGeneration: Int) {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(request.host)
            socket = DatagramSocket()
            socket.broadcast = false
            stateCallback(
                LivePreviewState(
                    active = true,
                    message = "Streaming",
                    host = request.host,
                    port = request.port,
                )
            )

            val frameIntervalMs = if (request.maxFps > 0) {
                max(1L, 1000L / request.maxFps.toLong())
            } else {
                0L
            }
            var nextFrameAt = SystemClock.elapsedRealtime()

            while (running.get() && activeGeneration == generation.get()) {
                val frame = capturePreviewFrame(request)
                if (frame == null) {
                    Thread.sleep(60)
                    nextFrameAt = SystemClock.elapsedRealtime()
                    continue
                }

                sendFrame(socket, address, request.port, frame)

                if (frameIntervalMs > 0L) {
                    nextFrameAt += frameIntervalMs
                    val sleepMs = nextFrameAt - SystemClock.elapsedRealtime()
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs)
                    } else {
                        nextFrameAt = SystemClock.elapsedRealtime()
                    }
                }
            }
        } catch (error: Exception) {
            if (activeGeneration == generation.get()) {
                stateCallback(
                    LivePreviewState(
                        active = false,
                        message = "Stream error",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                )
            }
        } finally {
            socket?.close()
        }
    }

    private fun capturePreviewFrame(request: LivePreviewStartRequest): CapturedPreviewFrame? {
        val imageReader = imageReaderProvider() ?: return null
        val image = imageReader.acquireLatestImage() ?: return null
        try {
            return captureImageFrame(image, request)
        } finally {
            image.close()
        }
    }

    private fun captureImageFrame(
        image: Image,
        request: LivePreviewStartRequest,
    ): CapturedPreviewFrame? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) {
            return null
        }

        val sourceWidth = image.width
        val sourceHeight = image.height
        var encodedFrame = EncodedFrame(
            jpegBytes = encodeJpeg(image, request.jpegQuality) ?: return null,
            width = sourceWidth,
            height = sourceHeight,
        )
        encodedFrame = resizeFrameIfNeeded(encodedFrame, request)

        return CapturedPreviewFrame(
            jpegBytes = encodedFrame.jpegBytes,
            width = encodedFrame.width,
            height = encodedFrame.height,
            timestampMs = System.currentTimeMillis(),
            rotationQuarterTurns = rotationQuarterTurnsProvider().and(0x03),
        )
    }

    private fun encodeJpeg(image: Image, jpegQuality: Int): ByteArray? {
        val nv21 = imageToNv21(image)
        val output = ByteArrayOutputStream()
        val success = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null,
        ).compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, output)
        return if (success) output.toByteArray() else null
    }

    private fun resizeFrameIfNeeded(
        frame: EncodedFrame,
        request: LivePreviewStartRequest,
    ): EncodedFrame {
        if (request.maxDimension <= 0) {
            return frame
        }

        val largestDimension = max(frame.width, frame.height)
        if (largestDimension <= request.maxDimension) {
            return frame
        }

        val bitmap = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size) ?: return frame
        val scale = request.maxDimension.toDouble() / largestDimension.toDouble()
        val targetWidth = max(2, (frame.width * scale).roundToInt())
        val targetHeight = max(2, (frame.height * scale).roundToInt())
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        return try {
            val output = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, request.jpegQuality, output)
            EncodedFrame(
                jpegBytes = output.toByteArray(),
                width = targetWidth,
                height = targetHeight,
            )
        } finally {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val output = ByteArray(ySize + (width * height / 2))

        copyPlane(
            plane = image.planes[0],
            planeWidth = width,
            planeHeight = height,
            output = output,
            offset = 0,
            outputStride = 1,
        )
        copyPlane(
            plane = image.planes[2],
            planeWidth = width / 2,
            planeHeight = height / 2,
            output = output,
            offset = ySize,
            outputStride = 2,
        )
        copyPlane(
            plane = image.planes[1],
            planeWidth = width / 2,
            planeHeight = height / 2,
            output = output,
            offset = ySize + 1,
            outputStride = 2,
        )

        return output
    }

    private fun copyPlane(
        plane: Image.Plane,
        planeWidth: Int,
        planeHeight: Int,
        output: ByteArray,
        offset: Int,
        outputStride: Int,
    ) {
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowData = ByteArray(rowStride)
        var outputIndex = offset

        for (row in 0 until planeHeight) {
            if (pixelStride == 1 && outputStride == 1) {
                buffer.get(output, outputIndex, planeWidth)
                outputIndex += planeWidth
                val padding = rowStride - planeWidth
                if (row < planeHeight - 1 && padding > 0 && buffer.remaining() >= padding) {
                    buffer.position(buffer.position() + padding)
                }
                continue
            }

            val bytesToRead = minOf(rowStride, buffer.remaining())
            if (bytesToRead <= 0) {
                break
            }
            buffer.get(rowData, 0, bytesToRead)
            for (column in 0 until planeWidth) {
                val rowIndex = column * pixelStride
                if (rowIndex >= bytesToRead || outputIndex >= output.size) {
                    break
                }
                output[outputIndex] = rowData[rowIndex]
                outputIndex += outputStride
            }
        }
    }

    private fun sendFrame(
        socket: DatagramSocket,
        address: InetAddress,
        port: Int,
        frame: CapturedPreviewFrame,
    ) {
        val frameId = nextFrameId.getAndIncrement()
        val totalBytes = frame.jpegBytes.size
        val chunkCount = max(1, (totalBytes + LIVE_PREVIEW_MAX_PACKET_PAYLOAD - 1) / LIVE_PREVIEW_MAX_PACKET_PAYLOAD)

        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * LIVE_PREVIEW_MAX_PACKET_PAYLOAD
            val end = minOf(totalBytes, start + LIVE_PREVIEW_MAX_PACKET_PAYLOAD)
            val payloadSize = end - start

            val packetBuffer = ByteBuffer
                .allocate(LIVE_PREVIEW_HEADER_SIZE + payloadSize)
                .order(ByteOrder.BIG_ENDIAN)
            packetBuffer.put(LIVE_PREVIEW_MAGIC)
            packetBuffer.put(LIVE_PREVIEW_VERSION)
            packetBuffer.put(frame.rotationQuarterTurns.toByte())
            packetBuffer.putInt(frameId)
            packetBuffer.putShort(chunkIndex.toShort())
            packetBuffer.putShort(chunkCount.toShort())
            packetBuffer.putShort(frame.width.toShort())
            packetBuffer.putShort(frame.height.toShort())
            packetBuffer.putInt(totalBytes)
            packetBuffer.putLong(frame.timestampMs)
            packetBuffer.put(frame.jpegBytes, start, payloadSize)

            val packetBytes = packetBuffer.array()
            socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
        }
    }
}

private data class EncodedFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)
