package com.marksimonlehner.capturebridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicInteger(0)
    private val sendLock = Object()

    @Volatile
    private var socket: Socket? = null
    private var lastHost: String? = null
    private var lastPort: Int? = null
    private var preferDiscovery = false
    private var discoveryOnResolved: ((String) -> Unit)? = null
    private var reconnectFuture: ScheduledFuture<*>? = null

    fun connect(host: String, port: Int, preferDiscovery: Boolean = false, quietRetry: Boolean = false) {
        this.preferDiscovery = preferDiscovery
        lastHost = host
        lastPort = port
        reconnectFuture?.cancel(false)
        val connectGeneration = generation.incrementAndGet()
        closeSocket()
        if (!quietRetry) {
            updateState(ConnectionState.Connecting)
        }

        connectExecutor.execute {
            try {
                val nextSocket = Socket()
                nextSocket.tcpNoDelay = true
                nextSocket.connect(InetSocketAddress(host, port), 3_000)
                if (connectGeneration != generation.get()) {
                    nextSocket.close()
                    return@execute
                }
                socket = nextSocket
                updateState(ConnectionState.Connected)
                sendHello()
                receiveLoop(nextSocket, connectGeneration)
            } catch (error: Exception) {
                if (connectGeneration == generation.get()) {
                    if (quietRetry) {
                        postDiscoveryStatus("Waiting for hub...")
                        updateState(ConnectionState.Idle)
                    } else {
                        updateState(ConnectionState.Failed(error.message ?: "Connection failed"))
                    }
                    scheduleReconnectIfNeeded()
                }
            }
        }
    }

    fun discoverAndConnect(port: Int, quietRetry: Boolean = false, onResolved: ((String) -> Unit)? = null) {
        preferDiscovery = true
        lastHost = null
        lastPort = port
        discoveryOnResolved = onResolved
        reconnectFuture?.cancel(false)
        generation.incrementAndGet()
        closeSocket()
        if (quietRetry) {
            updateState(ConnectionState.Idle)
            postDiscoveryStatus("Waiting for hub...")
        } else {
            updateState(ConnectionState.Connecting)
            postDiscoveryStatus("Discovering...")
        }

        connectExecutor.execute {
            try {
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true
                    udp.broadcast = true
                    udp.soTimeout = 1_500
                    udp.bind(InetSocketAddress(0))

                    val probe = "DISCOVER_UDPCAMERA\n".toByteArray(Charsets.UTF_8)
                    broadcastTargets().forEach { address ->
                        udp.send(DatagramPacket(probe, probe.size, address, port))
                    }

                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)

                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    val parts = response.split(Regex("\\s+"))
                    if (parts.firstOrNull() != "UDPCAMERA_OK") {
                        updateState(ConnectionState.Failed("Discovery bad response"))
                        return@execute
                    }

                    val resolvedHost = packet.address.hostAddress ?: packet.address.hostName
                    val resolvedPort = parts.getOrNull(1)?.toIntOrNull() ?: port
                    postDiscoveryStatus("Discovery OK")
                    mainHandler.post { discoveryOnResolved?.invoke(resolvedHost) }
                    connect(resolvedHost, resolvedPort, preferDiscovery = true, quietRetry = quietRetry)
                }
            } catch (_: SocketTimeoutException) {
                tryUsbReverseFallback(port, quietRetry)
            } catch (error: Exception) {
                if (quietRetry) {
                    postDiscoveryStatus("Waiting for hub...")
                    updateState(ConnectionState.Idle)
                } else {
                    updateState(ConnectionState.Failed(error.message ?: "Discovery failed"))
                }
                scheduleReconnectIfNeeded()
            }
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
        generation.incrementAndGet()
        reconnectFuture?.cancel(false)
        closeSocket()
        connectExecutor.shutdownNow()
        sendExecutor.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun receiveLoop(activeSocket: Socket, activeGeneration: Int) {
        try {
            activeSocket.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                while (activeGeneration == generation.get()) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        onCommand?.invoke(trimmed)
                    }
                }
            }
            if (activeGeneration == generation.get()) {
                updateState(ConnectionState.Idle)
                scheduleReconnectIfNeeded()
            }
        } catch (error: Exception) {
            if (activeGeneration == generation.get()) {
                updateState(ConnectionState.Failed(error.message ?: "Connection lost"))
                scheduleReconnectIfNeeded()
            }
        }
    }

    private fun sendHello() {
        sendLine("HELLO ${deviceDisplayName()}")
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

    private fun scheduleReconnectIfNeeded() {
        val port = lastPort ?: return
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({
            if (preferDiscovery) {
                discoverAndConnect(port, quietRetry = true, onResolved = discoveryOnResolved)
            } else {
                val host = lastHost
                if (host != null) {
                    connect(host, port, quietRetry = true)
                }
            }
        }, 1, TimeUnit.SECONDS)
    }

    private fun tryUsbReverseFallback(port: Int, quietRetry: Boolean) {
        if (!quietRetry) {
            postDiscoveryStatus("Discovery timeout, trying USB reverse...")
        }
        try {
            val host = "127.0.0.1"
            val nextSocket = Socket()
            nextSocket.tcpNoDelay = true
            nextSocket.connect(InetSocketAddress(host, port), 500)
            socket = nextSocket
            mainHandler.post { discoveryOnResolved?.invoke(host) }
            updateState(ConnectionState.Connected)
            sendHello()
            receiveLoop(nextSocket, generation.get())
        } catch (_: Exception) {
            postDiscoveryStatus("Waiting for hub...")
            updateState(ConnectionState.Idle)
            scheduleReconnectIfNeeded()
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses.toList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            networkInterface.interfaceAddresses
                .mapNotNull { it.broadcast }
                .forEach { addresses.add(it) }
        }
        return addresses.toList()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun updateState(next: ConnectionState) {
        _state.value = next
    }

    private fun postDiscoveryStatus(message: String) {
        mainHandler.post { onDiscoveryStatus?.invoke(message) }
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
}
