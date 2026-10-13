package com.marksimonlehner.capturebridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

enum class ConnectionTransport(val protocolValue: String) {
    UsbAdbReverse("usb_adb_reverse"),
    WifiDiscovery("wifi"),
    DirectHost("direct")
}

internal fun reconnectDelaySeconds(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
    0 -> 1L
    1 -> 2L
    2 -> 4L
    else -> 8L
}

private const val USB_POLL_SECONDS = 1L

internal fun parseDiscoveryResponse(text: String, defaultPort: Int): Int? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.firstOrNull() != "UDPCAMERA_OK") return null
    return parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
}

internal enum class DiscoveryErrorKind { NoNetwork, Blocked, Other }

private val NO_NETWORK_ERROR = Regex(
    "ENETUNREACH|ENETDOWN|EHOSTUNREACH|EADDRNOTAVAIL|Network is unreachable|Network is down|No route to host"
)
private val BLOCKED_ERROR = Regex("EPERM|EACCES|Operation not permitted|Permission denied")

internal fun classifyDiscoveryError(error: Throwable): DiscoveryErrorKind {
    for (current in generateSequence(error) { it.cause }.take(8)) {
        val message = current.message.orEmpty()
        if (current is NoRouteToHostException || NO_NETWORK_ERROR.containsMatchIn(message)) {
            return DiscoveryErrorKind.NoNetwork
        }
        if (BLOCKED_ERROR.containsMatchIn(message)) {
            return DiscoveryErrorKind.Blocked
        }
    }
    return DiscoveryErrorKind.Other
}

internal fun discoveryProbeTargets(
    subnetBroadcasts: List<InetAddress>,
    limitedBroadcast: InetAddress
): List<InetAddress> = (subnetBroadcasts + limitedBroadcast).distinct()

class TcpController(private val context: Context) {
    data class TransferFile(
        val file: File,
        val relativePath: String,
        val sizeBytes: Long
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    var onCommand: ((String) -> Unit)? = null
    var onDiscoveryStatus: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger(0)
    private val connectionLock = Object()
    private val sendLock = Object()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var lastHost: String? = null
    @Volatile
    private var lastPort: Int? = null
    @Volatile
    private var preferDiscovery = false
    @Volatile
    private var discoveryOnResolved: ((String) -> Unit)? = null
    private var reconnectFuture: ScheduledFuture<*>? = null

    // Retry backoff and USB probe result, only touched on connectExecutor.
    private var directAttempt = 0
    private var silentUsbAttempt = 0
    private var discoveryAttempt = 0
    private var nextUsbProbeAt = 0L
    private var nextDiscoveryAt = 0L
    private var usbTunnelSilent = false

    fun connect(
        host: String,
        port: Int,
        preferDiscovery: Boolean = false,
        quietRetry: Boolean = false,
        transport: ConnectionTransport = ConnectionTransport.DirectHost
    ) {
        this.preferDiscovery = preferDiscovery
        lastHost = host
        lastPort = port
        val owner = takeOwnership()
        if (!quietRetry) {
            updateState(ConnectionState.Connecting)
        }

        connectExecutor.execute {
            if (!quietRetry) {
                resetBackoff()
            }
            runDirectAttempt(owner, host, port, transport, quietRetry)
        }
    }

    fun discoverAndConnect(port: Int, quietRetry: Boolean = false, onResolved: ((String) -> Unit)? = null) {
        preferDiscovery = true
        lastHost = null
        lastPort = port
        discoveryOnResolved = onResolved
        val owner = takeOwnership()
        if (quietRetry) {
            updateState(ConnectionState.Idle)
        } else {
            updateState(ConnectionState.Connecting)
            postDiscoveryStatus("Trying USB...")
        }

        connectExecutor.execute {
            if (!quietRetry) {
                resetBackoff()
            }
            runDiscoveryCycle(owner, port, quietRetry)
        }
    }

    fun send(text: String) {
        sendLine(text)
    }

    fun sendLine(text: String) {
        sendExecutor.execute {
            try {
                sendLineBlocking(text)
            } catch (_: Exception) {
            }
        }
    }

    fun sendTransfer(
        name: String,
        files: List<TransferFile>,
        isAll: Boolean,
        completion: (Result<Unit>) -> Unit
    ) {
        sendExecutor.execute {
            val transferName = if (isAll) "ALL" else name
            try {
                val totalBytes = files.sumOf { it.sizeBytes }
                sendLineBlocking("TRANSFER_ACCEPTED $transferName")
                sendLineBlocking("TRANSFER_BEGIN $transferName ${files.size} $totalBytes")

                files.forEach { file ->
                    sendLineBlocking("FILE_BEGIN ${file.relativePath} ${file.sizeBytes}")
                    streamFile(file.file)
                    sendLineBlocking("FILE_DONE ${file.relativePath}")
                }

                if (isAll) {
                    sendLineBlocking("TRANSFER_ALL_DONE ${files.size} $totalBytes")
                } else {
                    sendLineBlocking("TRANSFER_DONE $name ${files.size}")
                }
                completion(Result.success(Unit))
            } catch (error: Exception) {
                try {
                    sendLineBlocking("TRANSFER_ERR $transferName ${formatTransferError(error)}")
                } catch (_: Exception) {
                }
                completion(Result.failure(error))
            }
        }
    }

    fun shutdown() {
        takeOwnership()
        connectExecutor.shutdownNow()
        sendExecutor.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun takeOwnership(): Int = synchronized(connectionLock) {
        reconnectFuture?.cancel(false)
        val owner = generation.incrementAndGet()
        closeSocket()
        owner
    }

    private fun isCurrent(owner: Int) = owner == generation.get()

    private fun runDiscoveryCycle(owner: Int, port: Int, quiet: Boolean) {
        if (!isCurrent(owner)) return
        val hubAnsweredOverUsb = SystemClock.elapsedRealtime() >= nextUsbProbeAt && tryUsbHub(owner, port)
        if (!hubAnsweredOverUsb && isCurrent(owner) && SystemClock.elapsedRealtime() >= nextDiscoveryAt) {
            tryWifiHub(owner, port, quiet)
        }
        // USB is polled every second regardless of the Wi-Fi backoff, so a fresh adb reverse is picked up quickly.
        scheduleNext(owner, USB_POLL_SECONDS)
    }

    private fun tryUsbHub(owner: Int, port: Int): Boolean {
        val host = "127.0.0.1"
        val usbSocket = try {
            openSocket(host, port, 500)
        } catch (_: Exception) {
            usbTunnelSilent = false
            return false
        }
        if (runSession(owner, usbSocket, ConnectionTransport.UsbAdbReverse, host)) {
            usbTunnelSilent = false
            return true
        }
        if (isCurrent(owner)) {
            usbTunnelSilent = true
            nextUsbProbeAt = SystemClock.elapsedRealtime() + reconnectDelaySeconds(silentUsbAttempt++) * 1_000L
            postDiscoveryStatus(USB_TUNNEL_SILENT_STATUS)
        }
        return false
    }

    private fun tryWifiHub(owner: Int, port: Int, quiet: Boolean) {
        if (!quiet) {
            postDiscoveryStatus("USB unavailable, discovering Wi-Fi...")
        }
        val (hubHost, hubPort) = try {
            discoverHub(port)
        } catch (error: Exception) {
            if (isCurrent(owner)) {
                deferDiscovery()
                reportDiscoveryFailure(error, quiet)
            }
            return
        }
        if (!isCurrent(owner)) return

        Log.i(NET_LOG_TAG, "Discovery reply from $hubHost, Hub TCP port $hubPort")
        postDiscoveryStatus("Discovery OK")
        mainHandler.post { discoveryOnResolved?.invoke(hubHost) }
        val hubSocket = try {
            openSocket(hubHost, hubPort, 3_000)
        } catch (error: Exception) {
            if (isCurrent(owner)) {
                deferDiscovery()
                reportAttemptFailure(error, quiet, "Connection failed")
            }
            return
        }
        if (!runSession(owner, hubSocket, ConnectionTransport.WifiDiscovery, hubHost) && isCurrent(owner)) {
            deferDiscovery()
            postDiscoveryStatus("Waiting for hub...")
        }
    }

    // Returns the host and TCP port of the first Hub that answers; throws SocketTimeoutException when none does.
    private fun discoverHub(port: Int): Pair<String, Int> {
        DatagramSocket(null).use { udp ->
            udp.reuseAddress = true
            udp.broadcast = true
            udp.bind(InetSocketAddress(0))

            val probe = "DISCOVER_UDPCAMERA\n".toByteArray(Charsets.UTF_8)
            val targets = discoveryProbeTargets(subnetBroadcastAddresses(), InetAddress.getByName("255.255.255.255"))
            val sendErrors = mutableListOf<Exception>()
            for (target in targets) {
                try {
                    udp.send(DatagramPacket(probe, probe.size, target, port))
                } catch (error: Exception) {
                    sendErrors.add(error)
                }
            }
            if (sendErrors.size == targets.size) {
                throw sendErrors.first()
            }

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            val deadline = SystemClock.elapsedRealtime() + 1_500L
            while (true) {
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 0) break
                udp.soTimeout = remainingMs.toInt()
                packet.length = buffer.size
                udp.receive(packet)
                val hubPort = parseDiscoveryResponse(String(packet.data, 0, packet.length, Charsets.UTF_8), port)
                if (hubPort != null) {
                    return (packet.address.hostAddress ?: packet.address.hostName) to hubPort
                }
            }
        }
        throw SocketTimeoutException("No discovery reply")
    }

    private fun runDirectAttempt(owner: Int, host: String, port: Int, transport: ConnectionTransport, quiet: Boolean) {
        if (!isCurrent(owner)) return
        try {
            val directSocket = openSocket(host, port, 3_000)
            if (!runSession(owner, directSocket, transport, host) && isCurrent(owner)) {
                postDiscoveryStatus("Waiting for hub...")
            }
        } catch (error: Exception) {
            if (isCurrent(owner)) {
                reportAttemptFailure(error, quiet, "Connection failed")
            }
        }
        if (isCurrent(owner)) {
            scheduleNext(owner, reconnectDelaySeconds(directAttempt++))
        }
    }

    // Returns whether the Hub sent at least one line; only then does the phone count as connected.
    // The Hub sends SYNC lines as soon as it accepts. A leftover adb reverse with no Hub behind it
    // still accepts the TCP connection and then closes it without a word, and a frozen Hub or
    // another program behind the port may accept and never answer.
    private fun runSession(owner: Int, nextSocket: Socket, transport: ConnectionTransport, host: String): Boolean {
        synchronized(connectionLock) {
            if (!isCurrent(owner)) {
                closeQuietly(nextSocket)
                return false
            }
            socket = nextSocket
        }

        var hubAnswered = false
        var sessionError: Exception? = null
        try {
            nextSocket.soTimeout = HUB_FIRST_LINE_TIMEOUT_MS
            sendHello(transport, host)
            nextSocket.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                while (isCurrent(owner)) {
                    val line = reader.readLine() ?: break
                    if (!hubAnswered) {
                        hubAnswered = true
                        nextSocket.soTimeout = 0
                        resetBackoff()
                        Log.i(NET_LOG_TAG, "Hub answered via ${transport.protocolValue} at $host")
                        if (transport == ConnectionTransport.UsbAdbReverse) {
                            mainHandler.post { discoveryOnResolved?.invoke(host) }
                        }
                        updateState(ConnectionState.Connected)
                        postDiscoveryStatus(
                            when (transport) {
                                ConnectionTransport.UsbAdbReverse -> "Connected via USB"
                                ConnectionTransport.WifiDiscovery -> "Connected via Wi-Fi"
                                ConnectionTransport.DirectHost -> "Connected"
                            }
                        )
                        postConnected()
                    }
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        onCommand?.invoke(trimmed)
                    }
                }
            }
        } catch (error: Exception) {
            sessionError = error
        } finally {
            synchronized(connectionLock) {
                if (socket === nextSocket) socket = null
            }
            closeQuietly(nextSocket)
        }

        if (!isCurrent(owner)) return hubAnswered
        if (!hubAnswered) {
            Log.w(
                NET_LOG_TAG,
                "${transport.protocolValue} connection to $host ended before the Hub sent anything",
                sessionError
            )
            updateState(ConnectionState.Idle)
            return false
        }
        if (sessionError == null) {
            Log.i(NET_LOG_TAG, "Hub session via ${transport.protocolValue} ended")
            updateState(ConnectionState.Idle)
        } else {
            Log.w(NET_LOG_TAG, "Hub session via ${transport.protocolValue} lost", sessionError)
            updateState(ConnectionState.Failed(sessionError.message ?: "Connection lost"))
        }
        postDiscoveryStatus("Hub disconnected - reconnecting...")
        return true
    }

    private fun sendHello(transport: ConnectionTransport, host: String) {
        sendLine("HELLO ${deviceDisplayName()}")
        val hostField = host.replace('\n', ' ').trim()
        sendLine("TRANSPORT ${transport.protocolValue} host=$hostField")
    }

    private fun sendLineBlocking(text: String) {
        val data = (text + "\n").toByteArray(Charsets.UTF_8)
        synchronized(sendLock) {
            val output = socket?.getOutputStream() ?: error("NOT_CONNECTED")
            output.write(data)
            output.flush()
        }
    }

    private fun streamFile(file: File) {
        synchronized(sendLock) {
            val output = socket?.getOutputStream() ?: error("NOT_CONNECTED")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
            output.flush()
        }
    }

    private fun scheduleNext(owner: Int, delaySeconds: Long) {
        synchronized(connectionLock) {
            if (!isCurrent(owner)) return
            reconnectFuture?.cancel(false)
            reconnectFuture = scheduler.schedule({
                if (isCurrent(owner)) {
                    connectExecutor.execute { runScheduledCycle(owner) }
                }
            }, delaySeconds, TimeUnit.SECONDS)
        }
    }

    private fun runScheduledCycle(owner: Int) {
        val port = lastPort ?: return
        if (preferDiscovery) {
            runDiscoveryCycle(owner, port, quiet = true)
        } else {
            val host = lastHost ?: return
            runDirectAttempt(owner, host, port, ConnectionTransport.DirectHost, quiet = true)
        }
    }

    private fun reportDiscoveryFailure(error: Exception, quiet: Boolean) {
        if (error is SocketTimeoutException) {
            updateState(ConnectionState.Idle)
            postWaitingStatus("Waiting for hub...")
            return
        }
        when (classifyDiscoveryError(error)) {
            DiscoveryErrorKind.NoNetwork -> {
                Log.i(NET_LOG_TAG, "Wi-Fi discovery has no network: ${error.message}")
                updateState(ConnectionState.Idle)
                postWaitingStatus("No Wi-Fi - waiting for Hub over USB")
            }
            DiscoveryErrorKind.Blocked -> {
                Log.w(NET_LOG_TAG, "Wi-Fi discovery blocked: ${error.message}")
                updateState(ConnectionState.Idle)
                postWaitingStatus("Wi-Fi discovery blocked - waiting for Hub over USB")
            }
            DiscoveryErrorKind.Other -> reportAttemptFailure(error, quiet, "Discovery failed")
        }
    }

    // A USB tunnel with no Hub behind it is the more useful hint, so the Wi-Fi result does not hide it.
    private fun postWaitingStatus(wifiStatus: String) {
        postDiscoveryStatus(if (usbTunnelSilent) USB_TUNNEL_SILENT_STATUS else wifiStatus)
    }

    private fun reportAttemptFailure(error: Exception, quiet: Boolean, fallbackMessage: String) {
        if (quiet) {
            updateState(ConnectionState.Idle)
            postDiscoveryStatus("Waiting for hub...")
        } else {
            updateState(ConnectionState.Failed(error.message ?: fallbackMessage))
        }
    }

    private fun subnetBroadcastAddresses(): List<InetAddress> {
        val interfaces: List<NetworkInterface> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val addresses = mutableListOf<InetAddress>()
        for (networkInterface in interfaces) {
            try {
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
                }
            } catch (_: Exception) {
            }
        }
        return addresses
    }

    private fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
        val nextSocket = Socket()
        try {
            nextSocket.tcpNoDelay = true
            nextSocket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (error: Exception) {
            closeQuietly(nextSocket)
            throw error
        }
        return nextSocket
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun closeQuietly(target: Socket) {
        try {
            target.close()
        } catch (_: Exception) {
        }
    }

    private fun updateState(next: ConnectionState) {
        _state.value = next
    }

    private fun postDiscoveryStatus(message: String) {
        mainHandler.post { onDiscoveryStatus?.invoke(message) }
    }

    private fun postConnected() {
        mainHandler.post { onConnected?.invoke() }
    }

    private fun deferDiscovery() {
        nextDiscoveryAt = SystemClock.elapsedRealtime() + reconnectDelaySeconds(discoveryAttempt++) * 1_000L
    }

    private fun resetBackoff() {
        directAttempt = 0
        silentUsbAttempt = 0
        discoveryAttempt = 0
        nextUsbProbeAt = 0L
        nextDiscoveryAt = 0L
    }

    private fun deviceDisplayName(): String {
        val configuredName = Settings.Global.getString(context.contentResolver, "device_name")
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (configuredName != null) {
            return configuredName
        }

        return listOf(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .replace('\n', ' ')
            .trim()
    }

    private fun formatTransferError(error: Exception): String {
        return when {
            error is IllegalStateException && error.message == "NOT_CONNECTED" -> "notConnected"
            !error.message.isNullOrBlank() -> error.message!!
            else -> error.toString()
        }
    }

    companion object {
        private const val NET_LOG_TAG = "CaptureBridgeNet"
        private const val HUB_FIRST_LINE_TIMEOUT_MS = 10_000
        private const val USB_TUNNEL_SILENT_STATUS = "USB tunnel open, Hub not answering - retrying"
    }
}
