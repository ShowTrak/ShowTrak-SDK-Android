package io.showtrak.sdk

import android.content.Context
import android.os.Build
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class RegisteredEvent(
    val id: String,
    val options: EventOptions,
    val handler: (Ack) -> Unit,
)

/**
 * Owns the Socket.IO connection and the full integrated-client protocol:
 * adoption, automatic heartbeats + metrics, action catalogue publishing, event
 * dispatch + feedback, and client-driven health state. This class contains no
 * UI; integrators interact with it via the [ShowTrak] facade.
 */
class ShowTrakClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("showtrak_sdk", Context.MODE_PRIVATE)
    private val metrics = MetricsCollector(appContext)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val events = ConcurrentHashMap<String, RegisteredEvent>()

    private var socket: Socket? = null
    private var clientId: String = ""
    private var adopted: Boolean = false
    private var serverHost: String? = null
    private var serverPort: Int? = null

    // Last health state requested by the host app; re-asserted on (re)connect.
    private var desiredState: String = "ONLINE"
    private var degradedMessage: String? = null

    private var heartbeatTask: ScheduledFuture<*>? = null
    private var adoptionTask: ScheduledFuture<*>? = null

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var stateListener: ((ConnectionState) -> Unit)? = null

    /** Friendly name shown in the adoption list / client tile. */
    var hostname: String = "ShowTrak (${Build.MANUFACTURER} ${Build.MODEL})"
        set(value) {
            val normalized = validateHostname(value)
            field = normalized
        }

    /** App version reported to the server. */
    var appVersion: String = "1.0.0"
        set(value) {
            val normalized = validateAppVersion(value)
            field = normalized
        }

    fun onStateChanged(listener: (ConnectionState) -> Unit) {
        stateListener = listener
        listener(state)
    }

    fun connect(ip: String, port: Int, id: String?) {
        val normalizedHost = validateServerHost(ip)
        validatePort(port)
        disconnect()
        clientId = resolveClientId(id)
        serverHost = normalizedHost
        serverPort = port
        adopted = prefs.getBoolean(adoptedKey(clientId), false)
        updateConnectionState(ConnectionState.CONNECTING)

        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            transports = arrayOf("websocket")
        }
        val query = "UUID=${enc(clientId)}&Adopted=$adopted&Integrated=true"
        val uri = "http://$normalizedHost:$port/?$query"
        val created = try {
            IO.socket(uri, options)
        } catch (e: URISyntaxException) {
            updateConnectionState(ConnectionState.DISCONNECTED)
            throw IllegalArgumentException("Invalid server address '$normalizedHost:$port'", e)
        }
        wire(created)
        socket = created
        created.connect()
    }

    fun disconnect() {
        cancelHeartbeat()
        cancelAdoption()
        socket?.let {
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    fun registerEvent(name: String, options: EventOptions, handler: (Ack) -> Unit) {
        val id = validateEventId(name)
        validateEventOptions(options, id)
        events[id] = RegisteredEvent(id, options, handler)
        publishActions()
    }

    fun unregisterEvent(name: String) {
        events.remove(validateEventId(name))
        publishActions()
    }

    fun setState(stateName: String, message: String?) {
        val normalized = validateSettableState(stateName)
        val normalizedMessage = validateStateMessage(normalized, message)
        desiredState = normalized
        degradedMessage = normalizedMessage
        socket?.takeIf { it.connected() }
            ?.emit("SetIntegratedState", normalized, normalizedMessage ?: "")
    }

    fun getClientId(): String? = clientId.ifBlank { null }

    fun isConnected(): Boolean = socket?.connected() == true

    fun isAdopted(): Boolean = adopted

    fun getServerHost(): String? = serverHost

    fun getServerPort(): Int? = serverPort

    fun getRegisteredEventIds(): List<String> = events.keys().toList().sorted()

    fun getStatus(): SDKStatus {
        return SDKStatus(
            connectionState = state,
            connected = isConnected(),
            adopted = adopted,
            clientId = getClientId(),
            hostname = hostname,
            appVersion = appVersion,
            desiredState = desiredState,
            degradedMessage = degradedMessage,
            serverHost = serverHost,
            serverPort = serverPort,
            registeredEventIds = getRegisteredEventIds(),
        )
    }

    // --- Socket wiring -------------------------------------------------------

    private fun wire(s: Socket) {
        s.on(Socket.EVENT_CONNECT) { _ ->
            if (adopted) goOnline() else startAdoptionLoop()
        }
        s.on(Socket.EVENT_DISCONNECT) { _ ->
            cancelHeartbeat()
            cancelAdoption()
            updateConnectionState(ConnectionState.CONNECTING)
        }
        s.on("Adopt") { _ ->
            adopted = true
            prefs.edit().putBoolean(adoptedKey(clientId), true).apply()
            cancelAdoption()
            goOnline()
        }
        s.on("Unadopt") { _ ->
            adopted = false
            prefs.edit().putBoolean(adoptedKey(clientId), false).apply()
            cancelHeartbeat()
            startAdoptionLoop()
        }
        s.on("TriggerIntegratedEvent") { args -> handleTrigger(args) }
    }

    private fun goOnline() {
        emitSystemInfo()
        publishActions()
        startHeartbeat()
        updateConnectionState(ConnectionState.ONLINE)
        // Re-assert the last requested health state after (re)connecting.
        setState(desiredState, degradedMessage)
    }

    private fun emitSystemInfo() {
        val info = JSONObject()
            .put("Hostname", hostname)
            .put("OperatingSystem", "Integrated")
            .put("MacAddresses", JSONObject())
        socket?.emit("SystemInfo", info)
    }

    private fun publishActions() {
        val s = socket ?: return
        if (!s.connected() || !adopted) return
        val array = JSONArray()
        for (event in events.values) {
            array.put(
                JSONObject()
                    .put("ID", event.id)
                    .put("Label", event.options.label ?: event.id)
                    .put("ColourIndex", event.options.colour)
                    .put("HasFeedback", event.options.hasFeedback)
            )
        }
        s.emit("RegisterActions", array)
    }

    private fun startHeartbeat() {
        cancelHeartbeat()
        heartbeatTask = scheduler.scheduleWithFixedDelay({
            try {
                val s = socket ?: return@scheduleWithFixedDelay
                if (!s.connected()) return@scheduleWithFixedDelay
                val payload = JSONObject()
                    .put("Version", appVersion)
                    .put("Vitals", metrics.vitals())
                s.emit("Heartbeat", payload)
            } catch (_: Exception) {
                // Heartbeats are best-effort; never crash the host app.
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun startAdoptionLoop() {
        updateConnectionState(ConnectionState.PENDING_ADOPTION)
        cancelAdoption()
        adoptionTask = scheduler.scheduleWithFixedDelay({
            try {
                val s = socket ?: return@scheduleWithFixedDelay
                if (!s.connected()) return@scheduleWithFixedDelay
                val payload = JSONObject()
                    .put("Hostname", hostname)
                    .put("Version", appVersion)
                s.emit("AdoptionHeartbeat", payload)
            } catch (_: Exception) {
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun handleTrigger(args: Array<out Any?>?) {
        val requestId = args?.getOrNull(0)?.toString() ?: return
        val eventId = args.getOrNull(1)?.toString() ?: return
        val event = events[eventId]
        if (event == null) {
            socket?.emit("IntegratedEventResponse", requestId, "Event not registered")
            return
        }
        val ack = object : Ack {
            override val requestId = requestId
            override val eventId = eventId
            private var done = false
            override fun success() = complete(null)
            override fun error(message: String) = complete(message)
            private fun complete(error: String?) {
                if (done) return
                done = true
                if (event.options.hasFeedback) {
                    socket?.emit(
                        "IntegratedEventResponse",
                        requestId,
                        error ?: JSONObject.NULL
                    )
                }
            }
        }
        try {
            event.handler(ack)
        } catch (e: Exception) {
            ack.error(e.message ?: "Handler error")
        }
    }

    private fun cancelHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    private fun cancelAdoption() {
        adoptionTask?.cancel(false)
        adoptionTask = null
    }

    private fun updateConnectionState(newState: ConnectionState) {
        state = newState
        stateListener?.invoke(newState)
    }

    private fun resolveClientId(id: String?): String {
        if (!id.isNullOrBlank()) return validateClientId(id)
        val existing = prefs.getString(GENERATED_ID_KEY, null)
        if (!existing.isNullOrBlank()) return validateClientId(existing)
        val generated = "STK-" + UUID.randomUUID().toString()
        prefs.edit().putString(GENERATED_ID_KEY, generated).apply()
        return generated
    }

    private fun adoptedKey(id: String) = "adopted_$id"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val GENERATED_ID_KEY = "generated_client_id"
        private const val MAX_CLIENT_ID_LENGTH = 128
        private const val MAX_HOST_LENGTH = 253
        private const val MAX_EVENT_LABEL_LENGTH = 80
        private const val MAX_STATE_MESSAGE_LENGTH = 256
        private const val MAX_HOSTNAME_LENGTH = 128
        private const val MAX_APP_VERSION_LENGTH = 64
        private val STATE_VALUES = setOf("ONLINE", "DEGRADED")
        private val CLIENT_ID_PATTERN = Regex("^[A-Za-z0-9_.:-]+$")
        private val EVENT_ID_PATTERN = Regex("^[A-Za-z0-9_.-]+$")
        private val HOST_PATTERN = Regex("^[A-Za-z0-9.-]+$")
        private val IPV4_PATTERN = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.|$)){4}$")

        private fun validateSettableState(stateName: String): String {
            val normalized = stateName.trim().uppercase(Locale.US)
            require(normalized in STATE_VALUES) {
                "Invalid state '$stateName'. Allowed values: ONLINE, DEGRADED"
            }
            return normalized
        }

        private fun validateStateMessage(state: String, message: String?): String? {
            val trimmed = message?.trim()
            if (state == "ONLINE") {
                require(trimmed.isNullOrEmpty()) {
                    "ONLINE state does not accept a message"
                }
                return null
            }
            require(!trimmed.isNullOrEmpty()) {
                "DEGRADED state requires a non-empty message"
            }
            require(trimmed.length <= MAX_STATE_MESSAGE_LENGTH) {
                "DEGRADED message must be <= $MAX_STATE_MESSAGE_LENGTH characters"
            }
            return trimmed
        }

        private fun validateClientId(rawId: String): String {
            val id = rawId.trim()
            require(id.isNotEmpty()) { "Client ID cannot be blank" }
            require(id.length <= MAX_CLIENT_ID_LENGTH) {
                "Client ID must be <= $MAX_CLIENT_ID_LENGTH characters"
            }
            require(id.matches(CLIENT_ID_PATTERN)) {
                "Client ID can only contain letters, digits, '.', '_', ':', '-'"
            }
            return id
        }

        private fun validateEventId(rawName: String): String {
            val id = rawName.trim()
            require(id.isNotEmpty()) { "Event name cannot be blank" }
            require(id.matches(EVENT_ID_PATTERN)) {
                "Event name must match ^[A-Za-z0-9_.-]+$ (got '$rawName')"
            }
            return id
        }

        private fun validateEventOptions(options: EventOptions, eventId: String) {
            require(options.colour in 0..7) {
                "Event '$eventId' colour must be between 0 and 7"
            }
            val label = options.label?.trim()
            if (options.label != null) {
                require(!label.isNullOrEmpty()) {
                    "Event '$eventId' label cannot be blank when provided"
                }
                require(label.length <= MAX_EVENT_LABEL_LENGTH) {
                    "Event '$eventId' label must be <= $MAX_EVENT_LABEL_LENGTH characters"
                }
            }
        }

        private fun validatePort(port: Int) {
            require(port in 1..65535) { "Port must be in range 1..65535 (got $port)" }
        }

        private fun validateServerHost(rawHost: String): String {
            val host = rawHost.trim()
            require(host.isNotEmpty()) { "Server host cannot be blank" }
            require(host.length <= MAX_HOST_LENGTH) {
                "Server host must be <= $MAX_HOST_LENGTH characters"
            }
            require(!host.contains("://")) {
                "Server host must not include protocol (use 'example.com', not 'http://example.com')"
            }
            require(!host.contains('/')) {
                "Server host must not include path segments"
            }
            require(host.matches(HOST_PATTERN) || host.matches(IPV4_PATTERN)) {
                "Server host can only contain letters, digits, '.', '-' and must be a hostname or IPv4"
            }
            return host
        }

        private fun validateHostname(rawHostname: String): String {
            val value = rawHostname.trim()
            require(value.isNotEmpty()) { "hostname cannot be blank" }
            require(value.length <= MAX_HOSTNAME_LENGTH) {
                "hostname must be <= $MAX_HOSTNAME_LENGTH characters"
            }
            return value
        }

        private fun validateAppVersion(rawVersion: String): String {
            val value = rawVersion.trim()
            require(value.isNotEmpty()) { "appVersion cannot be blank" }
            require(value.length <= MAX_APP_VERSION_LENGTH) {
                "appVersion must be <= $MAX_APP_VERSION_LENGTH characters"
            }
            return value
        }
    }
}
