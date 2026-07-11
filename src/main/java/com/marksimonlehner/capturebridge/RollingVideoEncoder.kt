package com.marksimonlehner.capturebridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class RollingVideoSegmentResult(
    val sampleCount: Int,
    val candidateSampleCount: Int,
    val candidateKeyframeCount: Int,
    val muxedKeyframeCount: Int,
    val firstPresentationUs: Long,
    val lastPresentationUs: Long,
    val requestedStartUs: Long,
    val requestedEndUs: Long,
    val requestedDurationUs: Long,
    val phoneStartRxElapsedNs: Long?,
    val phoneStopRxElapsedNs: Long?,
    val phoneRxDurationUs: Long?,
    val requestedMinusPhoneRxDurationUs: Long?,
    val muxedStartUs: Long,
    val muxedEndUs: Long,
    val prerollMs: Long,
    val cameraLeadUs: Long,
    val frameSelectionPolicy: String,
    val allIntra: Boolean,
    val muxedFirstIsKeyframe: Boolean,
    val chosenStartOffsetUs: Long,
    val nearestKeyframeBeforeStartOffsetUs: Long?,
    val nearestKeyframeAfterStartOffsetUs: Long?,
    val skippedSampleCountBeforeMuxStart: Int,
    val timestampSource: String,
    val actualMuxSelectionSource: String,
    val cameraTimeMuxApplied: Boolean,
    val cameraTimeSelectionAvailable: Boolean,
    val cameraTimeSelectionTrusted: Boolean,
    val cameraTimeUntrustedReason: String?,
    val cameraTimeChosenStartOffsetUs: Long?,
    val cameraTimeChosenEndOffsetUs: Long?,
    val cameraTimeVsCodecStartDeltaUs: Long?,
    val cameraTimeMatchMedianAbsUs: Long?,
    val cameraTimeMatchMaxAbsUs: Long?,
    val cameraTimeMatchUntrustedCount: Int,
    val cameraTimeMatchedSampleCount: Int,
    val cameraTimeSensorCallbackFps: Double?,
    val muxDurationUs: Long,
    val expectedFpsSampleCount: Int,
    val encodedBigGapCount: Int,
    val encodedMaxGapUs: Long,
    val encodedMissingFrameEstimate: Int,
    val encodedGapPositions: List<Int>,
    val finalWrittenSampleCount: Int,
    val finalFirstWrittenPtsUs: Long,
    val finalLastWrittenPtsUs: Long,
    val finalCutStartOffsetUs: Long,
    val finalCutEndOffsetUs: Long,
    val finalCutMatchesSelection: Boolean,
    val triggerClockMapperSource: String,
    val triggerClockElapsedMinusCodecUs: Long?,
    val configuredBitRate: Int,
    val configuredKeyFrameRateFps: Int,
    val configuredIFrameIntervalUs: Long
)

class RollingVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val orientationDegrees: Int,
    private val prerollMs: Long,
    private val tempDir: File,
    private val useTimestampedInput: Boolean = true,
    private val useSensorTimestampMapping: Boolean = false,
    private val collectSensorTimestampDiagnostics: Boolean = false
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

    private data class PendingEncodedSample(
        val flags: Int,
        val data: ByteArray
    )

    private data class SensorTimestampEvent(
        val index: Int,
        val frameNumber: Long,
        val sensorTimestampUs: Long,
        val callbackElapsedUs: Long,
        val callbackWallUs: Long
    )

    private data class EncodedTimestampEvent(
        val index: Int,
        val codecPresentationUs: Long,
        val flags: Int,
        val size: Int,
        val drainElapsedUs: Long,
        val drainWallUs: Long
    )

    private data class MuxSelectionDiagnostics(
        val candidateSampleCount: Int,
        val candidateKeyframeCount: Int,
        val muxedKeyframeCount: Int,
        val muxedFirstIsKeyframe: Boolean,
        val allCandidatesKeyframes: Boolean,
        val chosenStartOffsetUs: Long,
        val nearestKeyframeBeforeStartOffsetUs: Long?,
        val nearestKeyframeAfterStartOffsetUs: Long?,
        val skippedSampleCountBeforeMuxStart: Int
    )

    private data class MuxSelection(
        val samples: List<SpoolSample>,
        val diagnostics: MuxSelectionDiagnostics
    )

    private data class CameraTimeMatchedSample(
        val sample: SpoolSample,
        val cameraPresentationUs: Long,
        val matchErrorUs: Long
    )

    private data class CameraTimeSelectionDiagnostics(
        val available: Boolean,
        val trusted: Boolean,
        val untrustedReason: String?,
        val chosenStartOffsetUs: Long?,
        val chosenEndOffsetUs: Long?,
        val vsCodecStartDeltaUs: Long?,
        val medianAbsMatchUs: Long?,
        val maxAbsMatchUs: Long?,
        val untrustedCount: Int,
        val matchedSampleCount: Int,
        val sensorCallbackFps: Double?
    )

    private data class CameraTimeSelection(
        val samples: List<SpoolSample>,
        val diagnostics: CameraTimeSelectionDiagnostics
    )

    private data class EncodedGapDiagnostics(
        val muxDurationUs: Long,
        val expectedFpsSampleCount: Int,
        val bigGapCount: Int,
        val maxGapUs: Long,
        val missingFrameEstimate: Int,
        val gapPositions: List<Int>
    )

    private data class MuxWriteDiagnostics(
        val writtenSampleCount: Int,
        val firstWrittenPtsUs: Long,
        val lastWrittenPtsUs: Long
    )

    private val lock = Object()
    private val rollingSamples = ArrayDeque<MemorySample>()
    private val segmentSamples = mutableListOf<SpoolSample>()
    private val sensorTimestampQueueUs = ArrayDeque<Long>()
    private val pendingEncodedSamples = ArrayDeque<PendingEncodedSample>()
    private val sensorTimestampEvents = ArrayDeque<SensorTimestampEvent>()
    private val encodedTimestampEvents = ArrayDeque<EncodedTimestampEvent>()
    private val bufferInfo = MediaCodec.BufferInfo()
    private val retentionUs = max(1_000_000L, prerollMs * 1000L + 1_000_000L)
    private val sensorTimestampQueueLimit = max(120, fps.coerceAtLeast(1) * 2)
    private val sensorCodecAlignmentToleranceUs = max(10_000L, 2_000_000L / fps.coerceAtLeast(1))
    private val configuredKeyFrameRateFpsValue = fps.coerceAtLeast(1)
    private val configuredIFrameIntervalUsValue = 0L

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile
    private var draining = false
    private var outputFormat: MediaFormat? = null
    private var latestPresentationUs = 0L
    private var latestSensorTimestampUs = 0L
    private var latestInputPresentationUs = 0L
    private var latestInputWallNs = System.nanoTime()
    private var timestampedInput: TimestampedCameraInput? = null

    private var segmentFile: File? = null
    private var segmentData: RandomAccessFile? = null
    private var segmentActive = false
    private var requestedStartUs = 0L
    private var requestedEndUs = 0L
    private var segmentCameraLeadUs = 0L
    private var diagnosticRequestedStartElapsedUs: Long? = null
    private var diagnosticRequestedEndElapsedUs: Long? = null
    private var nextSensorTimestampEventIndex = 0
    private var nextEncodedTimestampEventIndex = 0

    fun start(): Surface {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val codecInputSurface = encoder.createInputSurface()
        val cameraSurface = if (useTimestampedInput) {
            val timestampInput = TimestampedCameraInput(
                codecInputSurface = codecInputSurface,
                width = width,
                height = height,
                onFrameSubmitted = { presentationUs ->
                    synchronized(lock) {
                        latestInputPresentationUs = presentationUs
                        latestInputWallNs = System.nanoTime()
                    }
                }
            )
            try {
                timestampInput.start()
            } catch (error: Exception) {
                codecInputSurface.release()
                encoder.release()
                throw error
            }.also {
                timestampedInput = timestampInput
            }
        } else {
            codecInputSurface
        }
        try {
            encoder.start()
        } catch (error: Exception) {
            timestampedInput?.stop()
            if (!useTimestampedInput) {
                codecInputSurface.release()
            }
            encoder.release()
            throw error
        }

        codec = encoder
        surface = cameraSurface
        draining = true
        drainThread = Thread({ drainLoop() }, "rolling-video-encoder").also { it.start() }
        requestKeyFrame()
        return cameraSurface
    }

    fun inputSurface(): Surface? = surface

    fun configuredBitRate(): Int = bitRate

    fun configuredKeyFrameRateFps(): Int = configuredKeyFrameRateFpsValue

    fun configuredIFrameIntervalUs(): Long = configuredIFrameIntervalUsValue

    fun isReady(): Boolean = synchronized(lock) {
        outputFormat != null && latestPresentationUs > 0L &&
            (!useTimestampedInput || latestInputPresentationUs > 0L) &&
            (!useSensorTimestampMapping || latestSensorTimestampUs > 0L)
    }

    fun beginSegment(
        commandReceivedWallNs: Long? = null,
        commandReceivedElapsedNs: Long? = null,
        targetElapsedNs: Long? = null,
        cameraLeadUs: Long = 0L
    ): Long {
        val startUs = synchronized(lock) {
            closeSegmentSpoolLocked(deleteFile = true)
            segmentSamples.clear()
            segmentCameraLeadUs = cameraLeadUs
            val requestedElapsedNs = targetElapsedNs ?: commandReceivedElapsedNs
            diagnosticRequestedStartElapsedUs = requestedElapsedNs?.let { it / 1000L }
            diagnosticRequestedEndElapsedUs = null
            requestedStartUs = estimatePresentationUsAtWallNsLocked(
                wallNs = commandReceivedWallNs,
                elapsedNs = requestedElapsedNs
            ) + segmentCameraLeadUs
            requestedEndUs = Long.MAX_VALUE
            segmentFile = File.createTempFile("capturebridge_segment_", ".h264buf", tempDir)
            segmentData = RandomAccessFile(segmentFile, "rw")
            if (prerollMs > 0L) {
                val earliestRollingUs = requestedStartUs - prerollMs * 1000L
                for (sample in rollingSamples) {
                    if (sample.presentationUs >= earliestRollingUs) {
                        appendSegmentSampleLocked(sample.presentationUs, sample.flags, sample.data)
                    }
                }
            }
            segmentActive = true
            requestedStartUs
        }
        requestKeyFrame()
        return startUs
    }

    fun endSegment(
        commandReceivedWallNs: Long? = null,
        commandReceivedElapsedNs: Long? = null,
        targetElapsedNs: Long? = null
    ): Long {
        val endUs = synchronized(lock) {
            val requestedElapsedNs = targetElapsedNs ?: commandReceivedElapsedNs
            diagnosticRequestedEndElapsedUs = requestedElapsedNs?.let { it / 1000L }
            requestedEndUs = estimatePresentationUsAtWallNsLocked(
                wallNs = commandReceivedWallNs,
                elapsedNs = requestedElapsedNs
            ) + segmentCameraLeadUs
            requestedEndUs
        }
        requestKeyFrame()
        return endUs
    }

    fun noteCameraSensorTimestamp(sensorTimestampNs: Long, frameNumber: Long = -1L) {
        if ((!useSensorTimestampMapping && !collectSensorTimestampDiagnostics) || sensorTimestampNs <= 0L) {
            return
        }
        val sensorTimestampUs = sensorTimestampNs / 1000L
        val callbackElapsedUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val callbackWallUs = System.nanoTime() / 1000L
        synchronized(lock) {
            if (collectSensorTimestampDiagnostics) {
                sensorTimestampEvents.add(
                    SensorTimestampEvent(
                        index = nextSensorTimestampEventIndex++,
                        frameNumber = frameNumber,
                        sensorTimestampUs = sensorTimestampUs,
                        callbackElapsedUs = callbackElapsedUs,
                        callbackWallUs = callbackWallUs
                    )
                )
                while (sensorTimestampEvents.size > TIMESTAMP_DIAGNOSTIC_EVENT_LIMIT) {
                    sensorTimestampEvents.removeFirst()
                }
            }
            if (useSensorTimestampMapping) {
                sensorTimestampQueueUs.addLast(sensorTimestampUs)
                while (sensorTimestampQueueUs.size > sensorTimestampQueueLimit) {
                    sensorTimestampQueueUs.removeFirst()
                }
                flushPendingSensorMappedSamplesLocked()
            }
        }
    }

    fun finishSegment(outputFile: File): RollingVideoSegmentResult {
        draining = false
        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        drainFor(1000L)
        val format: MediaFormat
        val samples: List<SpoolSample>
        val selectionDiagnostics: MuxSelectionDiagnostics
        val cameraSelectionDiagnostics: CameraTimeSelectionDiagnostics
        val cameraTimeMuxApplied: Boolean
        val encodedGapDiagnostics: EncodedGapDiagnostics
        val dataFile: File
        val startUs: Long
        val endUs: Long
        val startRxElapsedNs: Long?
        val endRxElapsedNs: Long?
        val triggerClockMapperSource: String
        val triggerClockElapsedMinusCodecUs: Long?
        val writeDiagnostics: MuxWriteDiagnostics
        synchronized(lock) {
            segmentActive = false
            format = outputFormat ?: throw IllegalStateException("Encoder output format is not ready")
            dataFile = segmentFile ?: throw IllegalStateException("No encoded segment data")
            segmentData?.fd?.sync()
            startUs = requestedStartUs
            endUs = requestedEndUs
            startRxElapsedNs = diagnosticRequestedStartElapsedUs?.let { it * 1000L }
            endRxElapsedNs = diagnosticRequestedEndElapsedUs?.let { it * 1000L }
            triggerClockMapperSource = triggerClockMapperSourceLocked()
            triggerClockElapsedMinusCodecUs = elapsedMinusCodecOffsetUsLocked()
            val codecSelection = chooseMuxSamplesLocked(startUs, endUs)
            val cameraSelection = chooseCameraTimeMuxSamplesLocked(codecSelection)
            cameraTimeMuxApplied = ENABLE_HIGH_SPEED_CAMERA_TIME_MUX_SELECTION && cameraSelection.diagnostics.trusted
            val selection = if (cameraTimeMuxApplied) cameraSelection.samples else codecSelection.samples
            samples = selection
            selectionDiagnostics = codecSelection.diagnostics
            cameraSelectionDiagnostics = cameraSelection.diagnostics
            encodedGapDiagnostics = encodedGapDiagnostics(samples)
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
        var writtenSampleCount = 0
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
                    if (pts <= lastWrittenUs) {
                        continue
                    }
                    lastWrittenUs = pts
                    writtenSampleCount += 1
                    info.set(0, bytes.size, pts, sample.flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(bytes), info)
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        }
        writeDiagnostics = MuxWriteDiagnostics(
            writtenSampleCount = writtenSampleCount,
            firstWrittenPtsUs = 0L,
            lastWrittenPtsUs = lastWrittenUs
        )

        synchronized(lock) {
            closeSegmentSpoolLocked(deleteFile = true)
            segmentSamples.clear()
        }

        return RollingVideoSegmentResult(
            sampleCount = samples.size,
            candidateSampleCount = selectionDiagnostics.candidateSampleCount,
            candidateKeyframeCount = selectionDiagnostics.candidateKeyframeCount,
            muxedKeyframeCount = selectionDiagnostics.muxedKeyframeCount,
            firstPresentationUs = samples.first().presentationUs,
            lastPresentationUs = samples.last().presentationUs,
            requestedStartUs = startUs,
            requestedEndUs = endUs,
            requestedDurationUs = endUs - startUs,
            phoneStartRxElapsedNs = startRxElapsedNs,
            phoneStopRxElapsedNs = endRxElapsedNs,
            phoneRxDurationUs = phoneRxDurationUs(startRxElapsedNs, endRxElapsedNs),
            requestedMinusPhoneRxDurationUs = phoneRxDurationUs(startRxElapsedNs, endRxElapsedNs)
                ?.let { (endUs - startUs) - it },
            muxedStartUs = samples.first().presentationUs,
            muxedEndUs = samples.last().presentationUs,
            prerollMs = prerollMs,
            cameraLeadUs = segmentCameraLeadUs,
            frameSelectionPolicy = FRAME_SELECTION_POLICY,
            allIntra = selectionDiagnostics.allCandidatesKeyframes,
            muxedFirstIsKeyframe = selectionDiagnostics.muxedFirstIsKeyframe,
            chosenStartOffsetUs = selectionDiagnostics.chosenStartOffsetUs,
            nearestKeyframeBeforeStartOffsetUs = selectionDiagnostics.nearestKeyframeBeforeStartOffsetUs,
            nearestKeyframeAfterStartOffsetUs = selectionDiagnostics.nearestKeyframeAfterStartOffsetUs,
            skippedSampleCountBeforeMuxStart = selectionDiagnostics.skippedSampleCountBeforeMuxStart,
            timestampSource = timestampSource(),
            actualMuxSelectionSource = if (cameraTimeMuxApplied) "camera_time" else "codec_time",
            cameraTimeMuxApplied = cameraTimeMuxApplied,
            cameraTimeSelectionAvailable = cameraSelectionDiagnostics.available,
            cameraTimeSelectionTrusted = cameraSelectionDiagnostics.trusted,
            cameraTimeUntrustedReason = cameraSelectionDiagnostics.untrustedReason,
            cameraTimeChosenStartOffsetUs = cameraSelectionDiagnostics.chosenStartOffsetUs,
            cameraTimeChosenEndOffsetUs = cameraSelectionDiagnostics.chosenEndOffsetUs,
            cameraTimeVsCodecStartDeltaUs = cameraSelectionDiagnostics.vsCodecStartDeltaUs,
            cameraTimeMatchMedianAbsUs = cameraSelectionDiagnostics.medianAbsMatchUs,
            cameraTimeMatchMaxAbsUs = cameraSelectionDiagnostics.maxAbsMatchUs,
            cameraTimeMatchUntrustedCount = cameraSelectionDiagnostics.untrustedCount,
            cameraTimeMatchedSampleCount = cameraSelectionDiagnostics.matchedSampleCount,
            cameraTimeSensorCallbackFps = cameraSelectionDiagnostics.sensorCallbackFps,
            muxDurationUs = encodedGapDiagnostics.muxDurationUs,
            expectedFpsSampleCount = encodedGapDiagnostics.expectedFpsSampleCount,
            encodedBigGapCount = encodedGapDiagnostics.bigGapCount,
            encodedMaxGapUs = encodedGapDiagnostics.maxGapUs,
            encodedMissingFrameEstimate = encodedGapDiagnostics.missingFrameEstimate,
            encodedGapPositions = encodedGapDiagnostics.gapPositions,
            finalWrittenSampleCount = writeDiagnostics.writtenSampleCount,
            finalFirstWrittenPtsUs = writeDiagnostics.firstWrittenPtsUs,
            finalLastWrittenPtsUs = writeDiagnostics.lastWrittenPtsUs,
            finalCutStartOffsetUs = samples.first().presentationUs - startUs,
            finalCutEndOffsetUs = samples.last().presentationUs - endUs,
            finalCutMatchesSelection = writeDiagnostics.firstWrittenPtsUs == 0L &&
                writeDiagnostics.writtenSampleCount == samples.size &&
                writeDiagnostics.lastWrittenPtsUs == samples.last().presentationUs - firstUs,
            triggerClockMapperSource = triggerClockMapperSource,
            triggerClockElapsedMinusCodecUs = triggerClockElapsedMinusCodecUs,
            configuredBitRate = bitRate,
            configuredKeyFrameRateFps = configuredKeyFrameRateFpsValue,
            configuredIFrameIntervalUs = configuredIFrameIntervalUsValue
        )
    }

    fun cameraTimeDiagnosticsJson(requestedStartUs: Long? = null, requestedEndUs: Long? = null): JSONObject {
        val sensorEvents: List<SensorTimestampEvent>
        val encodedEvents: List<EncodedTimestampEvent>
        synchronized(lock) {
            sensorEvents = sensorTimestampEvents.toList()
            encodedEvents = encodedTimestampEvents.toList()
        }
        val requestedStartElapsedUs = synchronized(lock) { diagnosticRequestedStartElapsedUs }
        val requestedEndElapsedUs = synchronized(lock) { diagnosticRequestedEndElapsedUs }
        val sensorIntervalsUs = sensorEvents.zipWithNext { a, b -> b.sensorTimestampUs - a.sensorTimestampUs }
            .filter { it > 0L }
        val encodedIntervalsUs = encodedEvents.zipWithNext { a, b -> b.codecPresentationUs - a.codecPresentationUs }
            .filter { it > 0L }
        val nearestDeltasUs = nearestSensorMinusCodecDeltas(sensorEvents, encodedEvents)
        val payload = JSONObject()
            .put("mode", "diagnostic_only")
            .put("collect_sensor_timestamp_diagnostics", collectSensorTimestampDiagnostics)
            .put("sensor_timestamp_mapping_enabled", useSensorTimestampMapping)
            .put("timestamp_source_active", timestampSource())
            .put("requested_start_us", requestedStartUs ?: JSONObject.NULL)
            .put("requested_end_us", requestedEndUs ?: JSONObject.NULL)
            .put("requested_start_elapsed_us", requestedStartElapsedUs ?: JSONObject.NULL)
            .put("requested_end_elapsed_us", requestedEndElapsedUs ?: JSONObject.NULL)
            .put("sensor_event_count", sensorEvents.size)
            .put("encoded_event_count", encodedEvents.size)
            .put("event_count_delta", sensorEvents.size - encodedEvents.size)
            .put("first_sensor_timestamp_us", sensorEvents.firstOrNull()?.sensorTimestampUs ?: JSONObject.NULL)
            .put("last_sensor_timestamp_us", sensorEvents.lastOrNull()?.sensorTimestampUs ?: JSONObject.NULL)
            .put("first_codec_presentation_us", encodedEvents.firstOrNull()?.codecPresentationUs ?: JSONObject.NULL)
            .put("last_codec_presentation_us", encodedEvents.lastOrNull()?.codecPresentationUs ?: JSONObject.NULL)
            .put("median_sensor_interval_us", medianLong(sensorIntervalsUs) ?: JSONObject.NULL)
            .put("median_encoded_interval_us", medianLong(encodedIntervalsUs) ?: JSONObject.NULL)
            .put("estimated_sensor_fps", fpsFromIntervalUs(medianLong(sensorIntervalsUs)))
            .put("estimated_encoded_fps", fpsFromIntervalUs(medianLong(encodedIntervalsUs)))
            .put("median_nearest_sensor_minus_codec_us", medianLong(nearestDeltasUs) ?: JSONObject.NULL)
            .put("min_nearest_sensor_minus_codec_us", nearestDeltasUs.minOrNull() ?: JSONObject.NULL)
            .put("max_nearest_sensor_minus_codec_us", nearestDeltasUs.maxOrNull() ?: JSONObject.NULL)
            .put(
                "first_sensor_minus_requested_start_elapsed_us",
                sensorEvents.firstOrNull()?.let { event ->
                    requestedStartElapsedUs?.let { event.sensorTimestampUs - it }
                } ?: JSONObject.NULL
            )
            .put(
                "last_sensor_minus_requested_end_elapsed_us",
                sensorEvents.lastOrNull()?.let { event ->
                    requestedEndElapsedUs?.let { event.sensorTimestampUs - it }
                } ?: JSONObject.NULL
            )
        payload.put("sensor_events", JSONArray().also { array ->
            sensorEvents.forEach { event ->
                array.put(
                    JSONObject()
                        .put("index", event.index)
                        .put("frame_number", event.frameNumber)
                        .put("sensor_timestamp_us", event.sensorTimestampUs)
                        .put("callback_elapsed_us", event.callbackElapsedUs)
                        .put("callback_wall_us", event.callbackWallUs)
                )
            }
        })
        payload.put("encoded_events", JSONArray().also { array ->
            encodedEvents.forEach { event ->
                array.put(
                    JSONObject()
                        .put("index", event.index)
                        .put("codec_presentation_us", event.codecPresentationUs)
                        .put("flags", event.flags)
                        .put("size", event.size)
                        .put("drain_elapsed_us", event.drainElapsedUs)
                        .put("drain_wall_us", event.drainWallUs)
                )
            }
        })
        return payload
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
        timestampedInput?.stop()
        timestampedInput = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        if (!useTimestampedInput) {
            try {
                surface?.release()
            } catch (_: Exception) {
            }
        }
        codec = null
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
        val drainElapsedUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val drainWallUs = System.nanoTime() / 1000L
        synchronized(lock) {
            if (collectSensorTimestampDiagnostics) {
                encodedTimestampEvents.add(
                    EncodedTimestampEvent(
                        index = nextEncodedTimestampEventIndex++,
                        codecPresentationUs = presentationUs,
                        flags = flags,
                        size = data.size,
                        drainElapsedUs = drainElapsedUs,
                        drainWallUs = drainWallUs
                    )
                )
                while (encodedTimestampEvents.size > TIMESTAMP_DIAGNOSTIC_EVENT_LIMIT) {
                    encodedTimestampEvents.removeFirst()
                }
            }
            if (useSensorTimestampMapping) {
                alignSensorTimestampQueueLocked(presentationUs)
                if (sensorTimestampQueueUs.isEmpty()) {
                    pendingEncodedSamples.addLast(PendingEncodedSample(flags, data))
                    while (pendingEncodedSamples.size > PENDING_ENCODED_SAMPLE_LIMIT) {
                        pendingEncodedSamples.removeFirst()
                    }
                    return
                }
                val sensorPresentationUs = sensorTimestampQueueUs.removeFirst()
                addMappedSampleLocked(sensorPresentationUs, flags, data)
                flushPendingSensorMappedSamplesLocked()
            } else {
                addMappedSampleLocked(presentationUs, flags, data)
            }
        }
    }

    private fun alignSensorTimestampQueueLocked(codecPresentationUs: Long) {
        val oldestAllowedUs = codecPresentationUs - sensorCodecAlignmentToleranceUs
        while (sensorTimestampQueueUs.size > 1 && sensorTimestampQueueUs.first < oldestAllowedUs) {
            sensorTimestampQueueUs.removeFirst()
        }
    }

    private fun flushPendingSensorMappedSamplesLocked() {
        while (pendingEncodedSamples.isNotEmpty() && sensorTimestampQueueUs.isNotEmpty()) {
            val pending = pendingEncodedSamples.removeFirst()
            val sensorPresentationUs = sensorTimestampQueueUs.removeFirst()
            addMappedSampleLocked(sensorPresentationUs, pending.flags, pending.data)
        }
    }

    private fun addMappedSampleLocked(presentationUs: Long, flags: Int, data: ByteArray) {
        latestPresentationUs = presentationUs
        if (useSensorTimestampMapping) {
            latestSensorTimestampUs = presentationUs
        }
        if (segmentActive) {
            appendSegmentSampleLocked(presentationUs, flags, data)
            return
        }
        val sample = MemorySample(presentationUs, flags, data)
        rollingSamples.addLast(sample)
        while (rollingSamples.isNotEmpty() && rollingSamples.first.presentationUs < presentationUs - retentionUs) {
            rollingSamples.removeFirst()
        }
    }

    private fun appendSegmentSampleLocked(presentationUs: Long, flags: Int, data: ByteArray) {
        val file = segmentData ?: return
        val offset = file.length()
        file.seek(offset)
        file.write(data)
        segmentSamples.add(SpoolSample(presentationUs, flags, offset, data.size))
    }

    private fun chooseMuxSamplesLocked(startUs: Long, endUs: Long): MuxSelection {
        val candidates = segmentSamples
            .sortedBy { it.presentationUs }
        if (candidates.isEmpty()) {
            return emptyMuxSelection()
        }

        val endIndex = candidates.indexOfFirst { it.presentationUs >= endUs }
            .takeIf { it >= 0 }
            ?: (candidates.size - 1)
        val selectable = candidates.take(endIndex + 1)
        if (selectable.isEmpty()) {
            return emptyMuxSelection()
        }

        val indexedSelectable = selectable.withIndex().toList()
        val indexedKeyframes = indexedSelectable
            .filter { (_index, sample) -> (sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
        val nearestKeyframeBeforeStart = indexedKeyframes
            .filter { it.value.presentationUs < startUs }
            .maxByOrNull { it.value.presentationUs }
        val nearestKeyframeAfterStart = indexedKeyframes
            .filter { it.value.presentationUs >= startUs }
            .minByOrNull { it.value.presentationUs }
        val nearestSyncStart = indexedKeyframes
            .minWithOrNull(
                compareBy<IndexedValue<SpoolSample>> { absUs(it.value.presentationUs - startUs) }
                    .thenBy { if (it.value.presentationUs >= startUs) 0 else 1 }
            )
        val nearestAnyStart = indexedSelectable
            .minWithOrNull(
                compareBy<IndexedValue<SpoolSample>> { absUs(it.value.presentationUs - startUs) }
                    .thenBy { if (it.value.presentationUs >= startUs) 0 else 1 }
            )
        val startIndex = nearestSyncStart?.index ?: nearestAnyStart?.index ?: 0
        val samples = candidates.subList(startIndex, endIndex + 1)
        val muxedKeyframeCount = samples.count { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
        return MuxSelection(
            samples = samples,
            diagnostics = MuxSelectionDiagnostics(
                candidateSampleCount = selectable.size,
                candidateKeyframeCount = indexedKeyframes.size,
                muxedKeyframeCount = muxedKeyframeCount,
                muxedFirstIsKeyframe = samples.firstOrNull()?.let { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 } ?: false,
                allCandidatesKeyframes = indexedKeyframes.size == selectable.size,
                chosenStartOffsetUs = samples.firstOrNull()?.let { it.presentationUs - startUs } ?: 0L,
                nearestKeyframeBeforeStartOffsetUs = nearestKeyframeBeforeStart?.let { it.value.presentationUs - startUs },
                nearestKeyframeAfterStartOffsetUs = nearestKeyframeAfterStart?.let { it.value.presentationUs - startUs },
                skippedSampleCountBeforeMuxStart = startIndex
            )
        )
    }

    private fun chooseCameraTimeMuxSamplesLocked(codecSelection: MuxSelection): CameraTimeSelection {
        val requestedStartElapsedUs = diagnosticRequestedStartElapsedUs
        val requestedEndElapsedUs = diagnosticRequestedEndElapsedUs
        if (!collectSensorTimestampDiagnostics || requestedStartElapsedUs == null || requestedEndElapsedUs == null) {
            return emptyCameraTimeSelection("NO_CAMERA_TIME_TARGET")
        }
        val sensorFps = sensorCallbackFps()
        if (sensorFps != null && sensorFps < fps.coerceAtLeast(1) * MIN_CAMERA_TIME_SENSOR_FPS_RATIO) {
            return emptyCameraTimeSelection(
                "SENSOR_CALLBACK_FPS_TOO_LOW_${sensorFps.formatOneDecimal()}_EXPECTED_$fps",
                sensorCallbackFps = sensorFps
            )
        }
        val candidates = segmentSamples.sortedBy { it.presentationUs }
        if (candidates.isEmpty()) {
            return emptyCameraTimeSelection("NO_SAMPLES", sensorCallbackFps = sensorFps)
        }
        val matched = cameraTimeMatchedSamples(candidates)
        if (matched.isEmpty()) {
            return emptyCameraTimeSelection("NO_CAMERA_TIME_MATCHES", sensorCallbackFps = sensorFps)
        }

        val targetStartUs = requestedStartElapsedUs + segmentCameraLeadUs
        val targetEndUs = requestedEndElapsedUs + segmentCameraLeadUs
        val endIndex = matched.indexOfFirst { it.cameraPresentationUs >= targetEndUs }
            .takeIf { it >= 0 }
            ?: (matched.size - 1)
        val selectable = matched.take(endIndex + 1)
        if (selectable.isEmpty()) {
            return emptyCameraTimeSelection("NO_SELECTABLE_CAMERA_TIME_SAMPLES", sensorCallbackFps = sensorFps)
        }

        val indexedSelectable = selectable.withIndex().toList()
        val indexedKeyframes = indexedSelectable
            .filter { (_index, matchedSample) ->
                (matchedSample.sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            }
        val nearestSyncStart = indexedKeyframes
            .minWithOrNull(
                compareBy<IndexedValue<CameraTimeMatchedSample>> {
                    absUs(it.value.cameraPresentationUs - targetStartUs)
                }.thenBy { if (it.value.cameraPresentationUs >= targetStartUs) 0 else 1 }
            )
        val nearestAnyStart = indexedSelectable
            .minWithOrNull(
                compareBy<IndexedValue<CameraTimeMatchedSample>> {
                    absUs(it.value.cameraPresentationUs - targetStartUs)
                }.thenBy { if (it.value.cameraPresentationUs >= targetStartUs) 0 else 1 }
            )
        val startIndex = nearestSyncStart?.index ?: nearestAnyStart?.index ?: 0
        val selected = selectable.subList(startIndex, endIndex + 1)
        if (selected.isEmpty()) {
            return emptyCameraTimeSelection("EMPTY_CAMERA_TIME_SELECTION", sensorCallbackFps = sensorFps)
        }

        val absErrors = selected.map { absUs(it.matchErrorUs) }
        val maxAbsError = absErrors.maxOrNull() ?: Long.MAX_VALUE
        val toleranceUs = cameraTimeMatchToleranceUs()
        val untrustedCount = absErrors.count { it > toleranceUs }
        val trusted = untrustedCount == 0
        val untrustedReason = if (trusted) {
            null
        } else {
            "CAMERA_TIME_MATCH_ERROR_GT_${toleranceUs}US"
        }
        val codecStartUs = codecSelection.samples.firstOrNull()?.presentationUs
        val selectedStart = selected.first()
        val selectedEnd = selected.last()
        return CameraTimeSelection(
            samples = selected.map { it.sample },
            diagnostics = CameraTimeSelectionDiagnostics(
                available = true,
                trusted = trusted,
                untrustedReason = untrustedReason,
                chosenStartOffsetUs = selectedStart.cameraPresentationUs - targetStartUs,
                chosenEndOffsetUs = selectedEnd.cameraPresentationUs - targetEndUs,
                vsCodecStartDeltaUs = codecStartUs?.let { selectedStart.sample.presentationUs - it },
                medianAbsMatchUs = medianLong(absErrors),
                maxAbsMatchUs = maxAbsError,
                untrustedCount = untrustedCount,
                matchedSampleCount = selected.size,
                sensorCallbackFps = sensorFps
            )
        )
    }

    private fun cameraTimeMatchedSamples(samples: List<SpoolSample>): List<CameraTimeMatchedSample> {
        val sensorTimes = sensorTimestampEvents
            .map { it.sensorTimestampUs }
            .sorted()
        if (sensorTimes.isEmpty()) {
            return emptyList()
        }
        val elapsedMinusWallUs = medianLong(
            encodedTimestampEvents.map { it.drainElapsedUs - it.drainWallUs }
        ) ?: return emptyList()
        return samples.map { sample ->
            val estimatedElapsedUs = sample.presentationUs + elapsedMinusWallUs
            val nearestSensorUs = nearestValue(sensorTimes, estimatedElapsedUs)
            CameraTimeMatchedSample(
                sample = sample,
                cameraPresentationUs = nearestSensorUs,
                matchErrorUs = nearestSensorUs - estimatedElapsedUs
            )
        }
    }

    private fun emptyCameraTimeSelection(reason: String, sensorCallbackFps: Double? = null): CameraTimeSelection =
        CameraTimeSelection(
            samples = emptyList(),
            diagnostics = CameraTimeSelectionDiagnostics(
                available = false,
                trusted = false,
                untrustedReason = reason,
                chosenStartOffsetUs = null,
                chosenEndOffsetUs = null,
                vsCodecStartDeltaUs = null,
                medianAbsMatchUs = null,
                maxAbsMatchUs = null,
                untrustedCount = 0,
                matchedSampleCount = 0,
                sensorCallbackFps = sensorCallbackFps
            )
        )

    private fun encodedGapDiagnostics(samples: List<SpoolSample>): EncodedGapDiagnostics {
        if (samples.isEmpty()) {
            return EncodedGapDiagnostics(
                muxDurationUs = 0L,
                expectedFpsSampleCount = 0,
                bigGapCount = 0,
                maxGapUs = 0L,
                missingFrameEstimate = 0,
                gapPositions = emptyList()
            )
        }
        val frameIntervalUs = 1_000_000.0 / fps.coerceAtLeast(1).toDouble()
        val bigGapThresholdUs = (frameIntervalUs * 1.5).toLong()
        val gaps = samples.zipWithNext { a, b -> b.presentationUs - a.presentationUs }
        val bigGapPositions = mutableListOf<Int>()
        var missingEstimate = 0
        gaps.forEachIndexed { index, gapUs ->
            if (gapUs > bigGapThresholdUs) {
                if (bigGapPositions.size < ENCODED_GAP_POSITION_LIMIT) {
                    bigGapPositions.add(index)
                }
                missingEstimate += max(0, (gapUs / frameIntervalUs).roundToInt() - 1)
            }
        }
        val durationUs = samples.last().presentationUs - samples.first().presentationUs
        val expectedCount = if (samples.size == 1) {
            1
        } else {
            (durationUs / frameIntervalUs).roundToInt() + 1
        }
        return EncodedGapDiagnostics(
            muxDurationUs = durationUs,
            expectedFpsSampleCount = expectedCount,
            bigGapCount = gaps.count { it > bigGapThresholdUs },
            maxGapUs = gaps.maxOrNull() ?: 0L,
            missingFrameEstimate = missingEstimate,
            gapPositions = bigGapPositions
        )
    }

    private fun emptyMuxSelection(): MuxSelection {
        return MuxSelection(
            samples = emptyList(),
            diagnostics = MuxSelectionDiagnostics(
                candidateSampleCount = 0,
                candidateKeyframeCount = 0,
                muxedKeyframeCount = 0,
                muxedFirstIsKeyframe = false,
                allCandidatesKeyframes = false,
                chosenStartOffsetUs = 0L,
                nearestKeyframeBeforeStartOffsetUs = null,
                nearestKeyframeAfterStartOffsetUs = null,
                skippedSampleCountBeforeMuxStart = 0
            )
        )
    }

    private fun estimateCurrentPresentationUsLocked(): Long {
        return estimatePresentationUsAtWallNsLocked(
            wallNs = System.nanoTime(),
            elapsedNs = SystemClock.elapsedRealtimeNanos()
        )
    }

    private fun estimatePresentationUsAtWallNsLocked(wallNs: Long? = null, elapsedNs: Long? = null): Long {
        if (useSensorTimestampMapping) {
            return (elapsedNs ?: SystemClock.elapsedRealtimeNanos()) / 1000L
        }
        if (latestInputPresentationUs <= 0L) {
            if (!useTimestampedInput) {
                val elapsedUs = (elapsedNs ?: SystemClock.elapsedRealtimeNanos()) / 1000L
                elapsedMinusCodecOffsetUsLocked()?.let { offsetUs ->
                    return elapsedUs - offsetUs
                }
                val referenceNs = wallNs ?: System.nanoTime()
                return referenceNs / 1000L
            }
            throw IllegalStateException("Timestamped encoder input is not ready")
        }
        if (useTimestampedInput) {
            return (elapsedNs ?: SystemClock.elapsedRealtimeNanos()) / 1000L
        }
        val referenceNs = wallNs ?: System.nanoTime()
        val elapsedUs = (referenceNs - latestInputWallNs) / 1000L
        return latestInputPresentationUs + elapsedUs
    }

    private fun elapsedMinusCodecOffsetUsLocked(): Long? {
        if (encodedTimestampEvents.isEmpty()) {
            return null
        }
        return medianLong(encodedTimestampEvents.map { it.drainElapsedUs - it.drainWallUs })
    }

    private fun triggerClockMapperSourceLocked(): String =
        when {
            useSensorTimestampMapping -> "camera2_sensor_elapsed"
            useTimestampedInput -> "timestamped_egl_wall"
            elapsedMinusCodecOffsetUsLocked() != null -> "elapsed_to_codec_median_offset"
            else -> "wall_clock_direct"
        }

    private fun phoneRxDurationUs(startRxElapsedNs: Long?, endRxElapsedNs: Long?): Long? {
        if (startRxElapsedNs == null || endRxElapsedNs == null) {
            return null
        }
        return (endRxElapsedNs - startRxElapsedNs) / 1000L
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

    private fun absUs(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) {
            return null
        }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2L
        }
    }

    private fun fpsFromIntervalUs(intervalUs: Long?): Any {
        if (intervalUs == null || intervalUs <= 0L) {
            return JSONObject.NULL
        }
        return 1_000_000.0 / intervalUs.toDouble()
    }

    private fun sensorCallbackFps(): Double? {
        val intervals = sensorTimestampEvents
            .map { it.sensorTimestampUs }
            .sorted()
            .zipWithNext { a, b -> b - a }
            .filter { it > 0L }
        val medianInterval = medianLong(intervals) ?: return null
        if (medianInterval <= 0L) {
            return null
        }
        return 1_000_000.0 / medianInterval.toDouble()
    }

    private fun cameraTimeMatchToleranceUs(): Long =
        max(1_000L, (500_000.0 / fps.coerceAtLeast(1).toDouble()).roundToInt().toLong())

    private fun Double.formatOneDecimal(): String =
        String.format(java.util.Locale.US, "%.1f", this)

    private fun nearestValue(sortedValues: List<Long>, target: Long): Long {
        if (sortedValues.isEmpty()) {
            return target
        }
        var low = 0
        var high = sortedValues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedValues[mid] < target) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val after = sortedValues.getOrNull(low)
        val before = sortedValues.getOrNull(low - 1)
        return when {
            before == null -> after ?: target
            after == null -> before
            absUs(after - target) < absUs(before - target) -> after
            else -> before
        }
    }

    private fun nearestSensorMinusCodecDeltas(
        sensorEvents: List<SensorTimestampEvent>,
        encodedEvents: List<EncodedTimestampEvent>
    ): List<Long> {
        if (sensorEvents.isEmpty() || encodedEvents.isEmpty()) {
            return emptyList()
        }
        val sensorTimes = sensorEvents.map { it.sensorTimestampUs }.sorted()
        var sensorIndex = 0
        val deltas = mutableListOf<Long>()
        for (encoded in encodedEvents.sortedBy { it.codecPresentationUs }) {
            while (
                sensorIndex + 1 < sensorTimes.size &&
                absUs(sensorTimes[sensorIndex + 1] - encoded.codecPresentationUs) <=
                    absUs(sensorTimes[sensorIndex] - encoded.codecPresentationUs)
            ) {
                sensorIndex += 1
            }
            deltas.add(sensorTimes[sensorIndex] - encoded.codecPresentationUs)
        }
        return deltas
    }

    private fun timestampSource(): String =
        when {
            useSensorTimestampMapping -> "camera2_sensor_timestamp_mapped"
            useTimestampedInput -> "surface_texture_egl_presentation_time"
            else -> "direct_mediacodec_input_surface_system_time"
        }

    companion object {
        private const val FRAME_SELECTION_POLICY = "nearest_keyframe_to_start_after_stop"
        private const val SENSOR_TIMESTAMP_QUEUE_LIMIT = 2048
        private const val PENDING_ENCODED_SAMPLE_LIMIT = 512
        private const val TIMESTAMP_DIAGNOSTIC_EVENT_LIMIT = 4096
        private const val ENCODED_GAP_POSITION_LIMIT = 32
        private const val ENABLE_HIGH_SPEED_CAMERA_TIME_MUX_SELECTION = true
        private const val MIN_CAMERA_TIME_SENSOR_FPS_RATIO = 0.75
    }

}
