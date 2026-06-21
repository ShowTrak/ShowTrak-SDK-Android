package io.showtrak.sdk

import android.content.Context

/**
 * Entry point for the ShowTrak Integration SDK.
 *
 * Typical usage:
 * ```
 * ShowTrak.connect(context, "10.0.0.10", 8000)            // ID auto-generated + persisted
 * ShowTrak.registerEvent("SetBoxRed", EventOptions("Set Box Red", colour = 0)) { ack ->
 *     // ... perform the action ...
 *     ack.success()
 * }
 * ShowTrak.setState("DEGRADED", "Projector offline")
 * ```
 *
 * The SDK owns the connection, adoption, heartbeats (with real metrics) and
 * feedback. It contains no UI.
 */
object ShowTrak {

    @Volatile
    private var client: ShowTrakClient? = null

    private fun requireClient(): ShowTrakClient =
        client ?: error("ShowTrak.connect(...) must be called before this operation")

    /**
     * Connect to a ShowTrak Server.
     *
     * @param id Optional unique Client ID. If null/blank the SDK generates a
     *           UUID and persists it across restarts; if provided it is used
     *           verbatim.
     */
    @JvmStatic
    @JvmOverloads
    fun connect(context: Context, ip: String, port: Int, id: String? = null) {
        val instance = client ?: ShowTrakClient(context).also { client = it }
        instance.connect(ip, port, id)
    }

    @JvmStatic
    fun disconnect() {
        client?.disconnect()
    }

    /** Register (or replace) an event the operator can trigger. */
    @JvmStatic
    @JvmOverloads
    fun registerEvent(
        name: String,
        options: EventOptions = EventOptions(),
        handler: (Ack) -> Unit,
    ) = requireClient().registerEvent(name, options, handler)

    /** Remove a previously registered event. */
    @JvmStatic
    fun unregisterEvent(name: String) = requireClient().unregisterEvent(name)

    /**
        * Report health. Only accepts "ONLINE" or "DEGRADED".
        * Invalid values throw [IllegalArgumentException].
     */
    @JvmStatic
    @JvmOverloads
    fun setState(state: String, message: String? = null) =
        requireClient().setState(state, message)

    /** Observe connection lifecycle changes (invoked off the main thread). */
    @JvmStatic
    fun onStateChanged(listener: (ConnectionState) -> Unit) =
        requireClient().onStateChanged(listener)

    /** Current SDK status snapshot (safe to call before connect). */
    @JvmStatic
    fun getStatus(): SDKStatus =
        client?.getStatus()
            ?: SDKStatus(
                connectionState = ConnectionState.DISCONNECTED,
                connected = false,
                adopted = false,
                clientId = null,
                hostname = "",
                appVersion = "",
                desiredState = "ONLINE",
                degradedMessage = null,
                serverHost = null,
                serverPort = null,
                registeredEventIds = emptyList(),
            )

    /** Resolved client ID after connect, or null if not initialized yet. */
    @JvmStatic
    fun getClientId(): String? = client?.getClientId()

    /** True when the underlying Socket.IO connection is currently connected. */
    @JvmStatic
    fun isConnected(): Boolean = client?.isConnected() == true

    /** True when this client is currently adopted on the server. */
    @JvmStatic
    fun isAdopted(): Boolean = client?.isAdopted() == true

    /** Last configured server host, or null if connect() was never called. */
    @JvmStatic
    fun getServerHost(): String? = client?.getServerHost()

    /** Last configured server port, or null if connect() was never called. */
    @JvmStatic
    fun getServerPort(): Int? = client?.getServerPort()

    /** Currently registered event IDs. */
    @JvmStatic
    fun getRegisteredEventIds(): List<String> = client?.getRegisteredEventIds() ?: emptyList()

    /** Current connection state (or DISCONNECTED before connect). */
    @JvmStatic
    val state: ConnectionState
        get() = client?.state ?: ConnectionState.DISCONNECTED
}
