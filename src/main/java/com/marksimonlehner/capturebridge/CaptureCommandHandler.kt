package com.marksimonlehner.capturebridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

internal data class ParsedCaptureCommand(
    val text: String,
    val head: String,
    val payload: String,
)

internal fun parseCaptureCommand(command: String): ParsedCaptureCommand {
    val trimmed = command.trim()
    val separator = trimmed.indexOf(' ')
    return ParsedCaptureCommand(
        text = trimmed,
        head = if (separator >= 0) trimmed.substring(0, separator) else trimmed,
        payload = if (separator >= 0) trimmed.substring(separator + 1) else "",
    )
}

internal fun handleTcpCommand(
    command: String,
    cameraController: CaptureCameraController,
    tcpController: TcpController,
    setStatus: (String) -> Unit,
) {
    val parsed = parseCaptureCommand(command)
    val head = parsed.head
    val payload = parsed.payload
    val phoneRxNs = SystemClock.elapsedRealtimeNanos()
    val phoneRxWallNs = System.nanoTime()

    when (head.uppercase()) {
        "NAME" -> {
            setStatus("Received NAME $payload")
            cameraController.setCaptureLabelFromTCP(payload)
            tcpController.sendLine(withPhoneTiming("NAME_OK", phoneRxNs))
            cameraController.prepareForRecording()
        }
        "PING" -> {
            tcpController.sendLine(withPhoneTiming("PONG $payload phone_elapsed_ns=${SystemClock.elapsedRealtimeNanos()}", phoneRxNs))
        }
        "SYNC" -> {
            val fields = parseProtocolFields(payload)
            val seq = payload.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                ?: fields["seq"]?.toLongOrNull()
                ?: -1L
            val hubTxNs = fields["hub_tx_ns"]?.toLongOrNull() ?: -1L
            tcpController.sendLine(
                "SYNC_OK seq=$seq hub_tx_ns=$hubTxNs " +
                    "phone_rx_ns=$phoneRxNs phone_tx_ns=${SystemClock.elapsedRealtimeNanos()}"
            )
        }
        "PREPARE", "ARM" -> {
            setStatus("Received $head")
            parsePrerollMs(payload)?.let { cameraController.setPrerollFromTCP(it) }
            parseCameraLeadMs(payload)?.let { cameraController.setCameraLeadFromTCP(it) }
            cameraController.prepareForRecording { response ->
                tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
            }
        }
        "START" -> {
            setStatus("Received START")
            cameraController.startRecording(
                commandReceivedWallNs = phoneRxWallNs,
                commandReceivedElapsedNs = phoneRxNs
            ) { response ->
                tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
            }
        }
        "START_AT" -> {
            val targetElapsedNs = parsePhoneElapsedNs(payload)
            if (targetElapsedNs == null) {
                tcpController.sendLine(withPhoneTiming("START_ERR BAD_TARGET", phoneRxNs))
                return
            }
            setStatus("Received START_AT")
            cameraController.startRecording(
                commandReceivedWallNs = phoneRxWallNs,
                commandReceivedElapsedNs = phoneRxNs,
                targetElapsedNs = targetElapsedNs
            ) { response ->
                tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
            }
        }
        "STOP" -> {
            setStatus("Received STOP")
            cameraController.stopRecording(
                commandReceivedWallNs = phoneRxWallNs,
                commandReceivedElapsedNs = phoneRxNs,
                onMarked = { response ->
                    tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                },
                onReady = { response ->
                    tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                },
                completion = { response ->
                    tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                }
            )
        }
        "STOP_AT" -> {
            val targetElapsedNs = parsePhoneElapsedNs(payload)
            if (targetElapsedNs == null) {
                tcpController.sendLine(withPhoneTiming("STOP_ERR BAD_TARGET", phoneRxNs))
                return
            }
            setStatus("Received STOP_AT")
            val delayMs = ((targetElapsedNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(0L)
            Handler(Looper.getMainLooper()).postDelayed({
                cameraController.stopRecording(
                    commandReceivedWallNs = phoneRxWallNs,
                    commandReceivedElapsedNs = phoneRxNs,
                    targetElapsedNs = targetElapsedNs,
                    onMarked = { response ->
                        tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                    },
                    onReady = { response ->
                        tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                    },
                    completion = { response ->
                        tcpController.sendLine(withPhoneTiming(response, phoneRxNs))
                    }
                )
            }, delayMs)
        }
        "LIST" -> {
            tcpController.sendLine("LIST_OK ${cameraController.buildCaptureListJSON()}")
        }
        "SETTINGS_LIST" -> {
            tcpController.sendLine("SETTINGS_LIST_OK ${cameraController.buildCameraSettingsJSON()}")
        }
        "SETTINGS" -> {
            cameraController.applyRemoteCameraSettings(payload) { response, error ->
                if (response != null) {
                    tcpController.sendLine("SETTINGS_OK $response")
                } else if (error != null) {
                    tcpController.sendLine("SETTINGS_ERR $error")
                }
            }
        }
        "GET" -> {
            if (payload.isBlank()) {
                tcpController.sendLine("TRANSFER_ERR UNKNOWN BAD_NAME")
                return
            }
            val busy = cameraController.transferBusyReason()
            if (busy != null) {
                tcpController.sendLine("BUSY $busy")
                return
            }
            cameraController.transferCapture(payload, tcpController)
        }
        "GET_ALL" -> {
            val busy = cameraController.transferBusyReason()
            if (busy != null) {
                tcpController.sendLine("BUSY $busy")
                return
            }
            cameraController.transferAllCaptures(tcpController)
        }
        "DELETE" -> {
            if (payload.isBlank()) {
                tcpController.sendLine("DELETE_ERR UNKNOWN BAD_NAME")
                return
            }
            val busy = cameraController.transferBusyReason()
            if (busy != null) {
                tcpController.sendLine("BUSY $busy")
                return
            }
            if (cameraController.deleteCapture(payload)) {
                tcpController.sendLine("DELETE_OK $payload")
            } else {
                tcpController.sendLine("DELETE_ERR $payload NOT_FOUND")
            }
        }
        "DELETE_ALL" -> {
            val busy = cameraController.transferBusyReason()
            if (busy != null) {
                tcpController.sendLine("BUSY $busy")
                return
            }
            if (cameraController.deleteAllCaptures()) {
                tcpController.sendLine("DELETE_OK ALL")
            } else {
                tcpController.sendLine("DELETE_ERR ALL FAILED")
            }
        }
        "LIVE_PREVIEW_START" -> {
            setStatus("Starting live preview stream")
            cameraController.startLivePreview(payload) { response ->
                tcpController.sendLine("LIVE_PREVIEW_STATE $response")
            }
        }
        "LIVE_PREVIEW_STOP" -> {
            setStatus("Stopping live preview stream")
            cameraController.stopLivePreview { response ->
                tcpController.sendLine("LIVE_PREVIEW_STATE $response")
            }
        }
        else -> {
            setStatus("Unknown command: ${parsed.text}")
            tcpController.send("ERR_UNKNOWN ${parsed.text}")
        }
    }
}

internal fun withPhoneTiming(response: String, phoneRxNs: Long): String =
    "$response phone_rx_ns=$phoneRxNs phone_tx_ns=${SystemClock.elapsedRealtimeNanos()}"

internal fun parseProtocolFields(payload: String): Map<String, String> =
    payload.trim().split(Regex("\\s+"))
        .filter { it.contains("=") }
        .mapNotNull { token ->
            val index = token.indexOf('=')
            if (index <= 0) {
                null
            } else {
                token.substring(0, index) to token.substring(index + 1)
            }
        }
        .toMap()

internal fun parsePhoneElapsedNs(payload: String): Long? {
    val text = payload.trim()
    if (text.isEmpty()) {
        return null
    }
    return try {
        if (text.startsWith("{")) {
            JSONObject(text).optLong("phone_elapsed_ns", -1L).takeIf { it > 0L }
        } else {
            parseProtocolFields(text)["phone_elapsed_ns"]?.toLongOrNull()?.takeIf { it > 0L }
        }
    } catch (_: Exception) {
        null
    }
}

internal fun parsePrerollMs(payload: String): Long? {
    val text = payload.trim()
    if (text.isEmpty()) {
        return null
    }
    return try {
        if (text.startsWith("{")) {
            JSONObject(text).optLong("prerollMs", -1L).takeIf { it >= 0L }
        } else {
            text.toLongOrNull()
        }
    } catch (_: Exception) {
        null
    }
}

internal fun parseCameraLeadMs(payload: String): Double? {
    val text = payload.trim()
    if (!text.startsWith("{")) {
        return null
    }
    return try {
        JSONObject(text).optDouble("cameraLeadMs", Double.NaN).takeIf { it.isFinite() }
    } catch (_: Exception) {
        null
    }
}
