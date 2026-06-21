package io.showtrak.sdk

/**
 * Options describing how an event (action) is presented and handled.
 *
 * @property label    Display label shown in the ShowTrak menu. Defaults to the event name.
 * @property colour   Palette index 0..7 (see the integration spec). Defaults to 7 (dark grey).
 * @property hasFeedback Whether the handler reports success/error back to the server.
 */
data class EventOptions(
    val label: String? = null,
    val colour: Int = 7,
    val hasFeedback: Boolean = true,
) {
    init {
        require(colour in 0..7) { "EventOptions.colour must be between 0 and 7" }
        if (label != null) {
            val normalized = label.trim()
            require(normalized.isNotEmpty()) {
                "EventOptions.label cannot be blank when provided"
            }
            require(normalized.length <= 80) {
                "EventOptions.label must be <= 80 characters"
            }
        }
    }
}

/**
 * Passed to an event handler when an operator triggers the event. The handler
 * must call exactly one of [success] / [error] when it is done (for
 * [EventOptions.hasFeedback] events; for fire-and-forget events the calls are
 * harmless no-ops).
 */
interface Ack {
    val requestId: String
    val eventId: String
    fun success()
    fun error(message: String)
}

/** High-level connection lifecycle, surfaced for optional UI. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    PENDING_ADOPTION,
    ONLINE,
}

/** Immutable snapshot of current SDK runtime state. */
data class SDKStatus(
    val connectionState: ConnectionState,
    val connected: Boolean,
    val adopted: Boolean,
    val clientId: String?,
    val hostname: String,
    val appVersion: String,
    val desiredState: String,
    val degradedMessage: String?,
    val serverHost: String?,
    val serverPort: Int?,
    val registeredEventIds: List<String>,
)
