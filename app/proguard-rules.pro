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

## Rules for youtubedl-android (yt-dlp fallback extractor, see
## extractor/YtDlpResolver.kt) -- this library bundles yt-dlp/ffmpeg
## running inside an embedded Python interpreter (Chaquopy), which
## instantiates its own classes via reflection at runtime rather than
## normal `new` calls R8 can see and trace. Without these keep rules,
## R8 strips or renames classes it thinks are unused/safe-to-rename,
## which breaks that reflection with errors like "class X is not a
## concrete class" or NoClassDefFoundError the moment init() actually
## runs on a release/minified build (works fine on debug builds where
## minification is off, which is why this wasn't caught earlier).
## These are the library's own official proguard rules (from its GitHub
## repo) -- org.apache.commons.compress.archivers.zip is the actual
## piece that was still missing after the first attempt at these rules:
## YoutubeDL.init() unpacks its bundled binaries via ZipUtils.unzip,
## which uses Commons Compress's zip reader through reflection too.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class com.chaquo.python.** { *; }
-dontwarn com.yausername.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.chaquo.python.**
-keepclassmembers class * {
    @com.chaquo.python.PyIgnore *;
}