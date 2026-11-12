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

## InnerTubeX + ktor (extractor/InnerTubeXResolver.kt). The library is kotlinx.serialization heavy and
## ships its own QuickJS bindings, so keep it whole instead of letting R8 guess.
-keep class com.metrolist.** { *; }
-keep class com.github.metrolistgroup.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn io.ktor.**
-dontwarn kotlinx.serialization.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

## the app bundles its own org.json (org.json:json in build.gradle.kts), keep it from being renamed
-keep class org.json.** { *; }
-dontwarn org.json.**

## native network helper (libsparkynet.so), JNI looks these up by name
-keep class dev.sparkynox.sparkytube.net.NativeNet { *; }

## native plugin api, plugins are compiled against these names
-keep class dev.sparkynox.sparkytube.plugins.api.** { *; }

## PO token WebView bridge (extractor/BotGuardTokenProvider.kt)
-keepclassmembers class dev.sparkynox.sparkytube.extractor.BotGuardTokenProvider$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
