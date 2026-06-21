# Consumer ProGuard rules for the ShowTrak SDK.
# Socket.IO / engine.io rely on reflection in places; keep their classes.
-keep class io.socket.** { *; }
-keep class io.showtrak.sdk.** { *; }
-dontwarn io.socket.**
