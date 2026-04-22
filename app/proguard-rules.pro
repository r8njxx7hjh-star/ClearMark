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

# ─── ZeroMark keep rules ────────────────────────────────────────────
# Keep the drawing engine classes fully intact.
# R8 must not rename or strip these since BrushDescriptor fields are
# accessed directly (no reflection, but the Java/Kotlin interop and
# CanvasFrontBufferedRenderer callbacks use class references that R8
# might otherwise inline or remove).
-keep class com.example.zeromark.Canvas.** { *; }
-keep class com.example.zeromark.Brushes.** { *; }
-keep class com.example.zeromark.tools.** { *; }

# Preserve line numbers for crash reporting (optional but recommended)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile