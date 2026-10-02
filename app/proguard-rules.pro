# Keep JS bridge class + methods (WebView reflection needs this)
-keepclassmembers class dev.sparkynox.sparkytube.JsBridge {
    public *;
}
-keep class dev.sparkynox.sparkytube.JsBridge { *; }

## Rules for NewPipeExtractor (its embedded Rhino JS interpreter, used to
## solve YouTube's signature-cipher / n-parameter obfuscation)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Don't warn on missing Java SE runtime classes referenced by Rhino
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.**

## Rules for the FFmpegKit fork (io.github.maxrave-dev:ffmpeg-kit-audio)
## used for adaptive-quality download muxing -- keeps its JNI-facing
## classes intact (native code calls back into these by name/signature,
## so R8 renaming/stripping them breaks the native<->Java bridge even
## though nothing in Kotlin source appears to reference them directly).
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

## yt-dlp runs through Chaquopy (extractor/PyYtDlp.kt). Chaquopy calls
## into Kotlin/Java classes by reflection from the Python side, so R8
## must not strip or rename them.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keepclassmembers class * {
    @com.chaquo.python.PyIgnore *;
}

## The app bundles its own org.json (org.json:json in build.gradle.kts), so R8
## treats it as app code and renames it. Chaquopy looks org.json.JSONObject up
## by name when it reads its build.json at startup, finds nothing that matches
## the renamed class and dies with an AssertionError -> yt-dlp never starts.
-keep class org.json.** { *; }
-dontwarn org.json.**
