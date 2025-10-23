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

# Keep all public APIs - these are meant to be used by consumers
-keep public class io.bearound.sdk.BeAround {
    public *;
    public static *;
}

# Keep public listener interfaces
-keep public interface io.bearound.sdk.BeaconEventListener {
    public *;
}

-keep public interface io.bearound.sdk.LogListener {
    public *;
}

# Keep public data classes and their properties
-keep public class io.bearound.sdk.BeaconData {
    public *;
}

-keep public class io.bearound.sdk.BeaconEventType {
    public *;
}

-keep public class io.bearound.sdk.SyncResult {
    public *;
}

-keep public class io.bearound.sdk.SyncResult$Success {
    public *;
}

-keep public class io.bearound.sdk.SyncResult$Error {
    public *;
}

# Keep public enums
-keep public enum io.bearound.sdk.BeAround$TimeScanBeacons {
    public *;
}

-keep public enum io.bearound.sdk.BeAround$SizeBackupLostBeacons {
    public *;
}

# Obfuscate all private and internal methods
-keepclassmembers class io.bearound.sdk.BeAround {
    private *;
    private static *;
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
