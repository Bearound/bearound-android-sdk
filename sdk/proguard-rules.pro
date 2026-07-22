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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep public class io.bearound.sdk.** {
    public *;
}

-keepclassmembers class io.bearound.sdk.** {
    public *;
}

-keep public interface io.bearound.sdk.** {
    public *;
}

# firebase-messaging is compileOnly (optional) — ignore its absent classes in R8
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
# Repackage obfuscated internals into a namespace unique to THIS SDK — the
# companion Bearound Telemetry SDK ships in the same app, and two R8-minified AARs
# both renaming internals to root-package short names (a.a, d.b) collide at build time.
-repackageclasses 'io.bearound.sdk.internal'
