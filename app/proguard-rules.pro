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

# -----------------------------------------------------------------------------
# ADD ALL THE RULES BELOW THIS LINE
# -----------------------------------------------------------------------------

# Keep JNI methods that are called from native code (Essential for MediaPipe)
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- MediaPipe Rules (CRITICAL) ---
# These rules prevent ProGuard from removing MediaPipe code needed for hand detection.
-keep public class com.google.mediapipe.tasks.** { *; }
-keep public class com.google.mediapipe.tasks.components.containers.** { *; }
-keep public class com.google.mediapipe.tasks.vision.handlandmarker.** { *; }
-keep public class com.google.mediapipe.framework.image.** { *; }


# --- CameraX Rules (Good Practice) ---
# These rules are often included automatically by the library, but it is safe to add them here.
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }

# --- Your Application's Services ---
# Ensure your services are not removed or renamed.
-keep class com.example.myapplication1.service.CameraService { *; }
-keep class com.example.myapplication1.service.OverlayService { *; }
-keep class com.example.myapplication1.service.GestureAccessibilityService { *; }