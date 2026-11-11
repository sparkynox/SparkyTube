// Top-level build file
plugins {
    id("com.android.application") version "8.13.0" apply false
    // 2.4.x because InnerTubeX is built with Kotlin 2.4 and the compiler can't read newer metadata than itself + 1
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
