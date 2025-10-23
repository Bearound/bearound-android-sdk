# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# ============================================================================
# BeAround SDK - ProGuard Rules
# ============================================================================

# Keep BeAround class - preserve all public APIs and companion object
-keep public class io.bearound.sdk.BeAround {
    public *;
    public static *;
}

# Explicitly keep companion object and ALL its members
-keep class io.bearound.sdk.BeAround$Companion {
    *;
}

# Keep all nested classes (enums)
-keep class io.bearound.sdk.BeAround$* {
    *;
}

# Keep public listener interfaces
-keep public interface io.bearound.sdk.BeaconEventListener {
    *;
}

-keep public interface io.bearound.sdk.LogListener {
    *;
}

# Keep public data classes and their properties
-keep public class io.bearound.sdk.BeaconData {
    *;
}

-keep public enum io.bearound.sdk.BeaconEventType {
    *;
}

-keep public class io.bearound.sdk.SyncResult {
    *;
}

-keep public class io.bearound.sdk.SyncResult$Success {
    *;
}

-keep public class io.bearound.sdk.SyncResult$Error {
    *;
}

# Keep annotations
-keepattributes *Annotation*

# Keep Kotlin metadata for the SDK
-keep class kotlin.Metadata { *; }

# Keep BuildConfig
-keep class io.bearound.sdk.BuildConfig { *; }

# Preserve generic signatures for Kotlin
-keepattributes Signature

# Keep companion objects
-keepclassmembers class * {
    ** Companion;
}

# ============================================================================
# Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ============================================================================
# AltBeacon Library
# ============================================================================
-keep class org.altbeacon.beacon.** { *; }
-keep interface org.altbeacon.beacon.** { *; }

# ============================================================================
# Google Play Services (Advertising ID)
# ============================================================================
-keep class com.google.android.gms.ads.identifier.** { *; }

# ============================================================================
# Optimization flags
# ============================================================================
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep line numbers for debugging
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
