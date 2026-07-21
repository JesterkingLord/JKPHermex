# ---------------------------------------------------------------------------
# JKPHermex / Hermex App ProGuard / R8 rules
# ---------------------------------------------------------------------------
# Why this file exists:
#   The release build type in app/build.gradle.kts has `isMinifyEnabled = true`
#   in v0.4.1+, which triggers R8 to shrink, optimise, and obfuscate the
#   release APK. R8 needs explicit hints for several libraries we use so the
#   app still works after minification. These rules are the minimum safe set
#   for the libraries declared in gradle/libs.versions.toml; tighten as you
#   encounter more issues in release builds.
#
# Test strategy:
#   - Run `./gradlew :app:assembleRelease` to confirm the release APK builds.
#   - Use `./scripts/verify-release.sh` to install the release APK and walk
#     through the core flows: onboarding, pair-from-URL, chat with reasoning
#     selector, list sessions, sign out.
#
# Last reviewed: 2026-07-15 against libs.versions.toml at that revision.
# ---------------------------------------------------------------------------

# --- Kotlin reflection (Compose lambdas, suspend functions, sealed types) ---
-dontwarn kotlin.reflect.jvm.internal.**
-keep class kotlin.Metadata { *; }

# --- kotlinx.coroutines (suspend fun reflection, ServiceLoader) ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
# Coroutines uses ServiceLoader on JVM/Android for its main thread dispatcher.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.flow.**internal**

# --- kotlinx.serialization (KSerializer reflection, generated $serializer) ---
# Without these rules R8 strips the generated serializer companions, causing
# `SerializationException: Serializer for class X is not found` at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hermexapp.android.**$$serializer { *; }
-keepclassmembers class com.hermexapp.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermexapp.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / Okio (conscrypt, platform reflection) ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.OkHttpClient { *; }
# SSE is class-loaded reflectively.
-keep class okhttp3.sse.** { *; }

# --- Room (generated *_Impl classes) ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# --- Compose runtime / lifecycle / viewmodel ---
# Compose's own consumer-rules ship inside the compose-runtime AAR, so we
# don't need to repeat them here. Lifecycle uses reflection for ViewModel
# constructors in some cases; keep them.
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# --- AppCompat / Material (transitive, no consumer rules upstream) ---
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**

# --- Hermex widget / service entry points (referenced from manifest only) ---
-keep class com.hermexapp.android.platform.HermexWidgetProvider { *; }
-keep class com.hermexapp.android.platform.ActiveRunService { *; }
-keep class com.hermexapp.android.HermexApp { *; }
-keep class com.hermexapp.android.MainActivity { *; }

# --- Strip build-time debug logs from release ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Keep line numbers so crash reports are useful, but rename everything else ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
