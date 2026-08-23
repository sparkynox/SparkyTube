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