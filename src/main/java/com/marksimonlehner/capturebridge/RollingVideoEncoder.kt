package com.marksimonlehner.capturebridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.view.Surface
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.max

data class RollingVideoSegmentResult(
    val sampleCount: Int,
    val firstPresentationUs: Long,
    val lastPresentationUs: Long,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
    val muxedStartUs: Long,
    val muxedEndUs: Long,
    val prerollMs: Long
)

class RollingVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val orientationDegrees: Int,
    private val prerollMs: Long,
    private val tempDir: File
) {
    private data class MemorySample(
        val presentationUs: Long,
        val flags: Int,
        val data: ByteArray
    )

    private data class SpoolSample(
        val presentationUs: Long,
        val flags: Int,
        val offset: Long,
        val size: Int
    )

    private val lock = Object()
    private val rollingSamples = ArrayDeque<MemorySample>()
    private val segmentSamples = mutableListOf<SpoolSample>()
    private val bufferInfo = MediaCodec.BufferInfo()
    private val retentionUs = max(1_000_000L, prerollMs * 1000L + 1_000_000L)

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var draining = false
    private var outputFormat: MediaFormat? = null
    private var latestPresentationUs = 0L
    private var latestSampleWallNs = System.nanoTime()

    private var segmentFile: File? = null
    private var segmentData: RandomAccessFile? = null
    private var segmentActive = false
    private var requestedStartUs = 0L
    private var requestedEndUs = 0L

    fun start(): Surface {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        surface = inputSurface
        draining = true
        drainThread = Thread({ drainLoop() }, "rolling-video-encoder").also { it.start() }
        requestKeyFrame()
        return inputSurface
    }

    fun inputSurface(): Surface? = surface

    fun isReady(): Boolean = synchronized(lock) {
        outputFormat != null && latestPresentationUs > 0L
    }

    fun beginSegment() {
        synchronized(lock) {
            closeSegmentSpoolLocked(deleteFile = true)
            segmentSamples.clear()
            requestedStartUs = estimateCurrentPresentationUsLocked() - prerollMs * 1000L
            requestedEndUs = Long.MAX_VALUE
            segmentFile = File.createTempFile("capturebridge_segment_", ".h264buf", tempDir)
            segmentData = RandomAccessFile(segmentFile, "rw")
            for (sample in rollingSamples) {
                appendSegmentSampleLocked(sample.presentationUs, sample.flags, sample.data)
            }
            segmentActive = true
        }
        requestKeyFrame()
    }

    fun endSegment() {
        synchronized(lock) {
            requestedEndUs = estimateCurrentPresentationUsLocked()
            segmentActive = false
        }
        requestKeyFrame()
    }

    fun finishSegment(outputFile: File): RollingVideoSegmentResult {
        drainFor(250L)
        val format: MediaFormat
        val samples: List<SpoolSample>
        val dataFile: File
        val startUs: Long
        val endUs: Long
        synchronized(lock) {
            format = outputFormat ?: throw IllegalStateException("Encoder output format is not ready")
            dataFile = segmentFile ?: throw IllegalStateException("No encoded segment data")
            segmentData?.fd?.sync()
            startUs = requestedStartUs
            endUs = requestedEndUs
            samples = chooseMuxSamplesLocked(startUs, endUs)
            if (samples.isEmpty()) {
                throw IllegalStateException("No encoded frames available for requested segment")
            }
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val firstUs = samples.first().presentationUs
        var lastWrittenUs = -1L
        RandomAccessFile(dataFile, "r").use { input ->
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                muxer.setOrientationHint(orientationDegrees)
                val track = muxer.addTrack(format)
                muxer.start()
                val info = MediaCodec.BufferInfo()
                for (sample in samples) {
                    val bytes = ByteArray(sample.size)
                    input.seek(sample.offset)
                    input.readFully(bytes)
                    val pts = sample.presentationUs - firstUs
                    if (pts < lastWrittenUs) {
                        continue
                    }
                    lastWrittenUs = pts
                    info.set(0, bytes.size, pts, sample.flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(bytes), info)
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        }

        synchronized(lock) {
            closeSegmentSpoolLocked(deleteFile = true)
            segmentSamples.clear()
        }

        return RollingVideoSegmentResult(
            sampleCount = samples.size,
            firstPresentationUs = samples.first().presentationUs,
            lastPresentationUs = samples.last().presentationUs,
            requestedStartUs = startUs,
            requestedEndUs = endUs,
            muxedStartUs = samples.first().presentationUs,
            muxedEndUs = samples.last().presentationUs,
            prerollMs = prerollMs
        )
    }

    fun stop() {
        draining = false
        try {
            drainThread?.join(600)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null

        try {
            codec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        drainFor(100L)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        synchronized(lock) {
            rollingSamples.clear()
            segmentSamples.clear()
            closeSegmentSpoolLocked(deleteFile = true)
        }
    }

    private fun drainLoop() {
        while (draining) {
            drainOnce(timeoutUs = 10_000L)
        }
    }

    private fun drainFor(durationMs: Long) {
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            drainOnce(timeoutUs = 0L)
            Thread.sleep(5L)
        }
    }

    private fun drainOnce(timeoutUs: Long) {
        val encoder = codec ?: return
        when (val index = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> return
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                synchronized(lock) {
                    outputFormat = encoder.outputFormat
                }
                return
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return
            else -> {
                if (index < 0) return
                val output = encoder.getOutputBuffer(index)
                if (output != null && bufferInfo.size > 0) {
                    val bytes = ByteArray(bufferInfo.size)
                    output.position(bufferInfo.offset)
                    output.limit(bufferInfo.offset + bufferInfo.size)
                    output.get(bytes)
                    onEncodedSample(bufferInfo.presentationTimeUs, bufferInfo.flags, bytes)
                }
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun onEncodedSample(presentationUs: Long, flags: Int, data: ByteArray) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return
        }
        synchronized(lock) {
            latestPresentationUs = presentationUs
            latestSampleWallNs = System.nanoTime()
            val sample = MemorySample(presentationUs, flags, data)
            rollingSamples.addLast(sample)
            while (rollingSamples.isNotEmpty() && rollingSamples.first.presentationUs < presentationUs - retentionUs) {
                rollingSamples.removeFirst()
            }
            if (segmentActive) {
                appendSegmentSampleLocked(sample.presentationUs, sample.flags, sample.data)
            }
        }
    }

    private fun appendSegmentSampleLocked(presentationUs: Long, flags: Int, data: ByteArray) {
        val file = segmentData ?: return
        val offset = file.length()
        file.seek(offset)
        file.write(data)
        segmentSamples.add(SpoolSample(presentationUs, flags, offset, data.size))
    }

    private fun chooseMuxSamplesLocked(startUs: Long, endUs: Long): List<SpoolSample> {
        val candidates = segmentSamples
            .filter { it.presentationUs <= endUs }
            .sortedBy { it.presentationUs }
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val syncBeforeStart = candidates.indexOfLast {
            it.presentationUs <= startUs && (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        }
        val firstSync = candidates.indexOfFirst {
            (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        }
        val startIndex = when {
            syncBeforeStart >= 0 -> syncBeforeStart
            firstSync >= 0 -> firstSync
            else -> 0
        }
        return candidates.drop(startIndex)
    }

    private fun estimateCurrentPresentationUsLocked(): Long {
        val elapsedUs = (System.nanoTime() - latestSampleWallNs) / 1000L
        return latestPresentationUs + elapsedUs.coerceAtLeast(0L)
    }

    private fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    private fun closeSegmentSpoolLocked(deleteFile: Boolean) {
        try {
            segmentData?.close()
        } catch (_: Exception) {
        }
        segmentData = null
        if (deleteFile) {
            try {
                segmentFile?.delete()
            } catch (_: Exception) {
            }
        }
        segmentFile = null
    }
}
