package com.marksimonlehner.capturebridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.marksimonlehner.capturebridge.ui.theme.CaptureBridgeTheme
import org.json.JSONObject

private const val SERVER_PORT = 6000

class MainActivity : ComponentActivity() {
    private lateinit var cameraController: CaptureCameraController
    private lateinit var tcpController: TcpController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraController = CaptureCameraController(applicationContext)
        tcpController = TcpController(applicationContext)

        setContent {
            CaptureBridgeTheme(dynamicColor = false) {
                CaptureBridgeScreen(cameraController, tcpController)
            }
        }
    }

    override fun onDestroy() {
        tcpController.shutdown()
        cameraController.stop()
        super.onDestroy()
    }
}

@Composable
private fun CaptureBridgeScreen(
    cameraController: CaptureCameraController,
    tcpController: TcpController
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val cameraState by cameraController.uiState.collectAsState()
    val connectionState by tcpController.state.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("Idle") }
    var serverIp by rememberSaveable { mutableStateOf("-") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(cameraController, tcpController) {
        tcpController.onDiscoveryStatus = { message -> statusText = message }
        tcpController.onCommand = { command ->
            handleTcpCommand(
                command = command,
                cameraController = cameraController,
                tcpController = tcpController,
                setStatus = { message ->
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        statusText = message
                    } else {
                        mainHandler.post { statusText = message }
                    }
                }
            )
        }
        onDispose {
            tcpController.onDiscoveryStatus = null
            tcpController.onCommand = null
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraController.start()
            statusText = "Discovering..."
            tcpController.discoverAndConnect(SERVER_PORT) { resolvedIp ->
                serverIp = resolvedIp
            }
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState != ConnectionState.Connected) {
            cameraController.stopLivePreview()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CaptureBridge",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "TCP camera recorder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ConnectionDot(connectionState.statusColor())
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionState.label(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (cameraState.currentPosition == CameraPosition.Front) {
                                cameraController.switchToBackCamera()
                            } else {
                                cameraController.switchToFrontCamera()
                            }
                        },
                        enabled = hasCameraPermission && !cameraState.isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (cameraState.currentPosition == CameraPosition.Front) "Back Camera" else "Front Camera")
                    }
                    Button(
                        onClick = {
                            statusText = "Discovering..."
                            tcpController.discoverAndConnect(SERVER_PORT) { resolvedIp ->
                                serverIp = resolvedIp
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect")
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                CameraPreviewBox(
                    hasCameraPermission = hasCameraPermission,
                    cameraController = cameraController,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusLine("Server", "$serverIp:$SERVER_PORT - ${connectionState.label()}")
                    StatusLine(
                        "Recording",
                        if (cameraState.isRecording) "Recording" else "Not recording",
                        if (cameraState.isRecording) MaterialTheme.colorScheme.tertiary else Color(0xFF2E7D32)
                    )
                    StatusLine("Status", statusText)
                    StatusLine("Label", cameraState.captureLabel.ifBlank { "-" })
                    if (cameraState.isTransferring) {
                        StatusLine("Transfer", "Sending files")
                    }
                    StatusLine("Camera", cameraState.cameraSettingsSummary)
                    StatusLine("Live Stream", cameraState.livePreviewSummary)
                    cameraState.lastError?.let { error ->
                        HorizontalDivider()
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewBox(
    hasCameraPermission: Boolean,
    cameraController: CaptureCameraController,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp)
            .heightIn(min = 320.dp, max = 520.dp)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also { cameraController.attachPreview(it) }
                },
                update = { textureView -> cameraController.attachPreview(textureView) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Allow Camera")
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(74.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectionDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun handleTcpCommand(
    command: String,
    cameraController: CaptureCameraController,
    tcpController: TcpController,
    setStatus: (String) -> Unit
) {
    val trimmed = command.trim()
    val separator = trimmed.indexOf(' ')
    val head = if (separator >= 0) trimmed.substring(0, separator) else trimmed
    val payload = if (separator >= 0) trimmed.substring(separator + 1) else ""
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
            setStatus("Unknown command: $trimmed")
            tcpController.send("ERR_UNKNOWN $trimmed")
        }
    }
}

private fun withPhoneTiming(response: String, phoneRxNs: Long): String =
    "$response phone_rx_ns=$phoneRxNs phone_tx_ns=${SystemClock.elapsedRealtimeNanos()}"

private fun parsePrerollMs(payload: String): Long? {
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

private fun parseCameraLeadMs(payload: String): Double? {
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

private fun ConnectionState.label(): String {
    return when (this) {
        ConnectionState.Idle -> "Disconnected"
        ConnectionState.Connecting -> "Connecting..."
        ConnectionState.Connected -> "Connected"
        is ConnectionState.Failed -> "Error: $message"
    }
}

private fun ConnectionState.statusColor(): Color {
    return when (this) {
        ConnectionState.Idle -> Color(0xFF77736C)
        ConnectionState.Connecting -> Color(0xFFB26A00)
        ConnectionState.Connected -> Color(0xFF2E7D32)
        is ConnectionState.Failed -> Color(0xFFB3261E)
    }
}
