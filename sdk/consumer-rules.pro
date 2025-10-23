# ============================================================================
# BeAround SDK - Consumer ProGuard Rules
# These rules will be applied to projects that consume this SDK
# ============================================================================

# Keep BeAround class completely - NO obfuscation on public members
-keep public class io.bearound.sdk.BeAround {
    *;
}

# CRITICAL: Keep companion object - this holds getInstance() and isInitialized()
-keepclassmembers class io.bearound.sdk.BeAround {
    ** Companion;
}

# CRITICAL: Keep ALL companion object methods and fields
-keep,allowobfuscation class io.bearound.sdk.BeAround$Companion {
    *;
}

# Keep companion object methods by name (explicit)
-keepclassmembers class io.bearound.sdk.BeAround$Companion {
    public ** getInstance(...);
    public ** isInitialized();
}

# Keep all nested classes (enums, companion)
-keep class io.bearound.sdk.BeAround$* {
    *;
}

# Additional safeguard - keep all static methods in BeAround
-keepclassmembers class io.bearound.sdk.BeAround {
    public static **;
}

# Keep all public interfaces
-keep public interface io.bearound.sdk.BeaconEventListener {
    public <methods>;
}

-keep public interface io.bearound.sdk.LogListener {
    public <methods>;
}

# Keep data classes - consumers need to access their properties
-keep public class io.bearound.sdk.BeaconData {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# Keep sealed class and its subclasses
-keep public class io.bearound.sdk.SyncResult {
    public <methods>;
    public <fields>;
}

-keep public class io.bearound.sdk.SyncResult$Success {
    public <init>(...);
    public <methods>;
    public <fields>;
}

-keep public class io.bearound.sdk.SyncResult$Error {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# Keep enums
-keep public enum io.bearound.sdk.BeaconEventType {
    **[] $VALUES;
    public *;
}

-keep public enum io.bearound.sdk.BeAround$TimeScanBeacons {
    **[] $VALUES;
    public *;
}

-keep public enum io.bearound.sdk.BeAround$SizeBackupLostBeacons {
    **[] $VALUES;
    public *;
}

# Keep Kotlin metadata
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Preserve generic signatures
-keepattributes Signature

# Keep inner classes
-keepattributes InnerClasses

# ============================================================================
# Dependencies - AltBeacon Library
# ============================================================================
-keep class org.altbeacon.beacon.** { *; }
-keep interface org.altbeacon.beacon.** { *; }
-dontwarn org.altbeacon.beacon.**

# ============================================================================
# Dependencies - Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Dependencies - Google Play Services
# ============================================================================
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.**
