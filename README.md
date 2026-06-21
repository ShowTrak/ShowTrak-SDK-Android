# ShowTrak Android SDK

Reusable Android SDK for ShowTrak integrated clients.

## Module

- `showtrak-sdk/` - Android library module (`io.showtrak.sdk`).

## API Overview

Primary entry point: `io.showtrak.sdk.ShowTrak`

Connection lifecycle:
- `connect(context, ip, port, id?)`
- `disconnect()`
- `onStateChanged { ... }`
- `state: ConnectionState`

Status and metadata getters:
- `getStatus(): SDKStatus`
- `getClientId(): String?`
- `isConnected(): Boolean`
- `isAdopted(): Boolean`
- `getServerHost(): String?`
- `getServerPort(): Int?`
- `getRegisteredEventIds(): List<String>`

Event registration:
- `registerEvent(name, options, handler)`
- `unregisterEvent(name)`

Health reporting:
- `setState("ONLINE")`
- `setState("DEGRADED", "message")`

Supported connection states:
- `DISCONNECTED`
- `CONNECTING`
- `PENDING_ADOPTION`
- `ONLINE`

## API Details

### connect

```kotlin
ShowTrak.connect(
	context = applicationContext,
	ip = "10.0.2.2",
	port = 8000,
	id = null
)
```

Behavior:
- If `id` is null or blank, SDK generates and persists a UUID-style client ID.
- If `id` is provided, it is used as-is.
- SDK handles adoption flow automatically.
- On reconnect, SDK restores adopted state for the active client ID.

### disconnect

```kotlin
ShowTrak.disconnect()
```

Behavior:
- Stops heartbeats and adoption loop.
- Closes socket connection.
- Updates state to `DISCONNECTED`.

### registerEvent

```kotlin
ShowTrak.registerEvent(
	name = "SetBoxRed",
	options = EventOptions(
		label = "Set Box Red",
		colour = 0,
		hasFeedback = true
	)
) { ack ->
	// Perform action
	ack.success()
}
```

Rules:
- Event name must match `^[A-Za-z0-9_.-]+$`.
- Registering the same name again replaces the previous handler.
- Events are published to server after adoption and whenever set changes.

### unregisterEvent

```kotlin
ShowTrak.unregisterEvent("SetBoxRed")
```

### setState

```kotlin
ShowTrak.setState("DEGRADED", "Projector not reachable")
ShowTrak.setState("ONLINE")
```

Rules:
- Accepted values: `ONLINE`, `DEGRADED`.
- Any other value throws `IllegalArgumentException`.
- `ONLINE` must not include a message.
- `DEGRADED` requires a non-empty message (max 256 chars).
- Last requested state is re-applied automatically after reconnect.

### onStateChanged and state

```kotlin
ShowTrak.onStateChanged { state ->
	// Called off main thread
}

val current = ShowTrak.state
```

Notes:
- Callback may run off the main thread; switch to UI thread before touching UI.

### getStatus and metadata

```kotlin
val status = ShowTrak.getStatus()

val state = status.connectionState
val connected = status.connected
val adopted = status.adopted
val clientId = status.clientId
val endpoint = "${status.serverHost}:${status.serverPort}"
val events = status.registeredEventIds
```

Quick getters:

```kotlin
val clientId = ShowTrak.getClientId()
val connected = ShowTrak.isConnected()
val adopted = ShowTrak.isAdopted()
val host = ShowTrak.getServerHost()
val port = ShowTrak.getServerPort()
val events = ShowTrak.getRegisteredEventIds()
```

## Data Types

### EventOptions

```kotlin
EventOptions(
	label = "Readable Label", // default: event name
	colour = 7,                // 0..7, default 7
	hasFeedback = true         // default true
)
```

Validation:
- `colour` must be in range `0..7`.
- If `label` is provided, it must be non-blank and `<= 80` chars.

## Validation and Errors

The SDK fails fast with `IllegalArgumentException` for invalid input.

Validated inputs include:
- `connect`: host format, host length, protocol/path rejection, and port range `1..65535`.
- `connect` ID: allowed characters and max length.
- `registerEvent`: event ID pattern and options validation.
- `setState`: allowed states and message rules.
- Mutable fields: `hostname` and `appVersion` must be non-blank and size-limited.

### Ack

Provided to each event handler:
- `ack.success()` reports successful completion.
- `ack.error("reason")` reports failed completion.

For `hasFeedback = false`, ack calls are harmless no-ops.

### SDKStatus

`SDKStatus` is an immutable snapshot returned by `ShowTrak.getStatus()`.

Fields:
- `connectionState: ConnectionState`
- `connected: Boolean`
- `adopted: Boolean`
- `clientId: String?`
- `hostname: String`
- `appVersion: String`
- `desiredState: String`
- `degradedMessage: String?`
- `serverHost: String?`
- `serverPort: Int?`
- `registeredEventIds: List<String>`

## Minimal Integration Example

```kotlin
class MainActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		ShowTrak.connect(applicationContext, ip = "10.0.2.2", port = 3000)

		ShowTrak.registerEvent("SetBoxBlue", EventOptions("Set Box Blue", colour = 2)) { ack ->
			runOnUiThread {
				// update UI
				ack.success()
			}
		}

		ShowTrak.onStateChanged { state ->
			runOnUiThread {
				// update connection indicator
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		ShowTrak.disconnect()
	}
}
```

## Networking Notes

- Emulator-to-host IP is typically `10.0.2.2`.
- SDK uses Socket.IO websocket transport.
- Heartbeat interval is 1 second.

## Protocol Reference

Full protocol and behavior reference:
- `SHOWTRAK_INTEGRATION_SDK.md`

## Usage in another project

From a consuming Gradle project (for example the demo app), include the module
as a project dependency and map it to this repository path:

```kotlin
include(":showtrak-sdk")
project(":showtrak-sdk").projectDir = file("../ShowTrak-SDK-Android/showtrak-sdk")
```

Then depend on it:

```kotlin
implementation(project(":showtrak-sdk"))
```

This keeps a single SDK source of truth shared across repos.
