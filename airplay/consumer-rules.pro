# Adapted from jqssun/android-airplay-server v0.0.36 (a46cca0); modified by GlassOS Aerial, 2026-10-25
# Original work by jqssun and contributors, licensed under the GNU GPL v3 (see THIRD_PARTY_NOTICES.md).
# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
