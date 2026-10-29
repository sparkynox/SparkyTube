package dev.sparkynox.sparkytube.logs

import android.content.Context
import java.io.File

// catches C++ crashes (SIGSEGV...) that the Kotlin crash handler never sees, see nativecrash.cpp.
// the report is read on the next start and goes into the same "App crashed last time" screen
object NativeCrash {

    private val loaded = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeInit(path: String): Boolean
    private external fun nativeCrashNow()

    private fun file(c: Context) = File(c.filesDir, "native_crash.txt")

    // run this before LogRecorder.init, that wipes the last session log the report wants to include
    fun consume(c: Context) {
        try {
            val f = file(c)
            if (f.exists() && f.length() > 0) {
                LogRecorder.recordNativeCrash(c, f.readText())
            }
            f.delete()
        } catch (_: Exception) {
        }
    }

    fun install(c: Context) {
        if (loaded) nativeInit(file(c).absolutePath)
    }

    // on purpose, for testing the whole flow
    fun testCrash() {
        if (loaded) nativeCrashNow()
    }
}
