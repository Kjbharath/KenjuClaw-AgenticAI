# Add project specific ProGuard rules here.

# ── Timber ─────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**

# ── QNN / Qualcomm native libs ──────────────────────────────────────────────
# Keep JNI method names used by QNN runtime reflection
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── KenjuClaw hardware config (used reflectively) ──────────────────────────
-keep class com.kenju.claw.hardware.** { *; }
-keep class com.kenju.claw.KenjuClawApplication { *; }

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
