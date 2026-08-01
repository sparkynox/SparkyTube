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