package com.mightyfrog.stratagemcaller.wifi

import android.content.Context
import android.net.wifi.WifiManager
import com.mightyfrog.stratagemcaller.macro.MacroDirection
import com.mightyfrog.stratagemcaller.macro.MacroOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

data class GameKeyMapping(
    val activate: String = "LEFTCTRL",
    val up: String = "UP",
    val down: String = "DOWN",
    val left: String = "LEFT",
    val right: String = "RIGHT"
) {
    fun validationError(): String? {
        val keys = listOf(activate, up, down, left, right).map { it.trim().uppercase() }
        if (keys.any(String::isEmpty)) return "All five game keys are required."
        if (keys.toSet().size != keys.size) return "The activation and direction keys must be different."
        val supported = Regex("^(?:[A-Z]|[0-9]|F(?:[1-9]|1[0-2])|LEFTCTRL|CTRL|RIGHTCTRL|LEFTSHIFT|SHIFT|RIGHTSHIFT|LEFTALT|ALT|RIGHTALT|SPACE|UP|DOWN|LEFT|RIGHT)$")
        if (keys.any { !supported.matches(it) }) {
            return "Use letters, numbers, arrows, CTRL, SHIFT, ALT, SPACE, or F1–F12."
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("activate", activate.trim().uppercase())
        .put("up", up.trim().uppercase())
        .put("down", down.trim().uppercase())
        .put("left", left.trim().uppercase())
        .put("right", right.trim().uppercase())
}

enum class WifiLinkPhase { STARTING, WAITING, REQUEST_PENDING, CONNECTING, CONNECTED, ERROR }

data class WifiLinkState(
    val phase: WifiLinkPhase = WifiLinkPhase.STARTING,
    val peerAddress: String? = null,
    val detail: String? = null
) {
    val isConnected: Boolean get() = phase == WifiLinkPhase.CONNECTED
}

private data class PairingDecision(val allowed: Boolean, val mapping: GameKeyMapping)
private class ReceiverCommandException(message: String) : IOException(message)

class WifiMacroOutput(context: Context) : MacroOutput {
    companion object {
        const val TCP_PORT = 38573
        const val DISCOVERY_PORT = 38574
        private const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestMutex = Mutex()
    private val mutableState = MutableStateFlow(WifiLinkState())
    val state = mutableState.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var pendingApproval: CompletableDeferred<PairingDecision>? = null
    @Volatile private var trustedPeerAddress: String? = null
    @Volatile private var currentMapping = GameKeyMapping()
    private var multicastLock: WifiManager.MulticastLock? = null

    val isConnected: Boolean
        get() = mutableState.value.isConnected && activeSocket?.isClosed == false

    suspend fun start(mapping: GameKeyMapping) = withContext(Dispatchers.IO) {
        if (serverSocket?.isClosed == false) {
            currentMapping = mapping
            return@withContext
        }

        currentMapping = mapping
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.soTimeout = 1_000
            server.bind(InetSocketAddress(TCP_PORT))
        } catch (error: IOException) {
            server.close()
            mutableState.value = WifiLinkState(
                phase = WifiLinkPhase.ERROR,
                detail = "Could not start the phone link on port $TCP_PORT: ${error.message}"
            )
            return@withContext
        }

        serverSocket = server
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("StratagemCallerDiscovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        mutableState.value = WifiLinkState(phase = WifiLinkPhase.WAITING)
        serviceScope.launch { listenForDecks() }
        serviceScope.launch { acceptDeckConnections(server) }
    }

    fun approveConnection(mapping: GameKeyMapping) {
        currentMapping = mapping
        pendingApproval?.let { approval ->
            trustedPeerAddress = mutableState.value.peerAddress
            mutableState.value = WifiLinkState(
                phase = WifiLinkPhase.CONNECTING,
                peerAddress = mutableState.value.peerAddress
            )
            approval.complete(PairingDecision(allowed = true, mapping = mapping))
        }
    }

    fun rejectConnection() {
        pendingApproval?.complete(PairingDecision(allowed = false, mapping = currentMapping))
    }

    suspend fun updateMapping(mapping: GameKeyMapping) {
        currentMapping = mapping
        if (activeSocket?.isClosed == false) {
            request(JSONObject().put("type", "configure").put("mapping", mapping.toJson()))
            mutableState.value = WifiLinkState(
                phase = WifiLinkPhase.CONNECTED,
                peerAddress = mutableState.value.peerAddress
            )
        }
    }

    fun stop() {
        serviceScope.cancel()
        pendingApproval?.cancel()
        pendingApproval = null
        trustedPeerAddress = null
        activeSocket?.close()
        activeSocket = null
        serverSocket?.close()
        serverSocket = null
        reader = null
        writer = null
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }

    override suspend fun activate() {
        request(JSONObject().put("type", "activate"))
    }

    override suspend fun direction(direction: MacroDirection) {
        request(JSONObject().put("type", "direction").put("direction", direction.name).put("pressed", true))
    }

    override suspend fun releaseDirection(direction: MacroDirection) {
        request(JSONObject().put("type", "direction").put("direction", direction.name).put("pressed", false))
    }

    override suspend fun releaseAll() {
        request(JSONObject().put("type", "releaseAll"))
    }

    private suspend fun listenForDecks() = withContext(Dispatchers.IO) {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 1_000
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                val buffer = ByteArray(1_024)
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val discovery = try {
                        JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    } catch (_: JSONException) {
                        continue
                    }
                    if (discovery.optString("type") != "discover" || discovery.optInt("version") != 1) {
                        continue
                    }
                    val offer = JSONObject()
                        .put("type", "offer")
                        .put("version", 1)
                        .put("port", TCP_PORT)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(offer, offer.size, packet.address, packet.port))
                }
            }
        } catch (error: SocketException) {
            if (serverSocket?.isClosed == false) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.ERROR,
                    detail = "Wi-Fi discovery stopped: ${error.message}"
                )
            }
        } catch (error: IOException) {
            if (serverSocket?.isClosed == false) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.ERROR,
                    detail = "Wi-Fi discovery failed: ${error.message}"
                )
            }
        }
    }

    private suspend fun acceptDeckConnections(server: ServerSocket) = withContext(Dispatchers.IO) {
        while (currentCoroutineContext().isActive && !server.isClosed) {
            try {
                handleDeck(server.accept())
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: SocketException) {
                if (!server.isClosed) {
                    mutableState.value = WifiLinkState(
                        phase = WifiLinkPhase.ERROR,
                        detail = "Phone link stopped: ${error.message}"
                    )
                }
                break
            } catch (error: IOException) {
                if (!server.isClosed) {
                    mutableState.value = WifiLinkState(
                        phase = WifiLinkPhase.ERROR,
                        detail = "Phone link failed: ${error.message}"
                    )
                }
                break
            }
        }
    }

    private suspend fun handleDeck(socket: Socket) {
        val peer = socket.inetAddress?.hostAddress ?: "Windows receiver"
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val incomingReader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val outgoingWriter = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        var connectionApproval: CompletableDeferred<PairingDecision>? = null
        try {
            val helloLine = incomingReader.readLine()
            if (helloLine == null) {
                throw IOException("The receiver closed before requesting approval.")
            }
            val hello = JSONObject(helloLine)
            if (hello.optString("type") != "hello" || hello.optInt("version") != 1) {
                write(outgoingWriter, JSONObject().put("ok", false).put("error", "Unsupported receiver protocol."))
                return
            }

            val decision = if (trustedPeerAddress == peer) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.CONNECTING,
                    peerAddress = peer
                )
                PairingDecision(allowed = true, mapping = currentMapping)
            } else {
                val approval = CompletableDeferred<PairingDecision>()
                connectionApproval = approval
                pendingApproval = approval
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.REQUEST_PENDING,
                    peerAddress = peer
                )
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { approval.await() }
            }
            pendingApproval = null
            if (decision == null || !decision.allowed) {
                write(outgoingWriter, JSONObject().put("ok", false).put("error", "Connection was not approved."))
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.WAITING,
                    detail = "Receiver connection declined."
                )
                return
            }

            write(outgoingWriter, JSONObject().put("ok", true))
            socket.soTimeout = 5_000
            activeSocket = socket
            reader = incomingReader
            writer = outgoingWriter
            try {
                request(JSONObject().put("type", "configure").put("mapping", decision.mapping.toJson()))
                trustedPeerAddress = peer
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.CONNECTED,
                    peerAddress = peer
                )
            } catch (error: ReceiverCommandException) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.ERROR,
                    peerAddress = peer,
                    detail = error.message ?: "The receiver rejected the keyboard mapping."
                )
            }

            while (currentCoroutineContext().isActive && activeSocket === socket) {
                delay(2_500)
                request(JSONObject().put("type", "ping"))
            }
        } catch (error: IOException) {
            if (serverSocket?.isClosed == false) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.ERROR,
                    peerAddress = peer,
                    detail = error.message ?: "Connection to the receiver was lost."
                )
            }
        } catch (error: JSONException) {
            if (serverSocket?.isClosed == false) {
                mutableState.value = WifiLinkState(
                    phase = WifiLinkPhase.ERROR,
                    peerAddress = peer,
                    detail = "The receiver sent an invalid message."
                )
            }
        } finally {
            if (pendingApproval === connectionApproval) pendingApproval = null
            if (activeSocket === socket) {
                activeSocket = null
                reader = null
                writer = null
            }
            socket.close()
            if (serverSocket?.isClosed == false && mutableState.value.phase != WifiLinkPhase.ERROR) {
                mutableState.value = WifiLinkState(phase = WifiLinkPhase.WAITING)
            }
        }
    }

    private suspend fun request(message: JSONObject) = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            val activeReader = reader ?: throw IOException("The Steam Deck receiver is not connected.")
            val activeWriter = writer ?: throw IOException("The Steam Deck receiver is not connected.")
            try {
                write(activeWriter, message)
                checkResponse(activeReader.readLine())
            } catch (error: ReceiverCommandException) {
                throw error
            } catch (error: IOException) {
                activeSocket?.close()
                throw error
            } catch (error: JSONException) {
                activeSocket?.close()
                throw error
            }
        }
    }

    private fun write(writer: BufferedWriter, message: JSONObject) {
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }

    private fun checkResponse(line: String?) {
        if (line == null) throw IOException("The Steam Deck receiver stopped responding.")
        val response = JSONObject(line)
        if (!response.optBoolean("ok")) {
            throw ReceiverCommandException(
                response.optString("error").ifBlank { "The receiver rejected the command." }
            )
        }
    }
}
