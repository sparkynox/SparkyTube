package dev.sparkynox.sparkytube.logs

import android.app.Application
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

/**
 * Wraps Thread.setDefaultUncaughtExceptionHandler so an unhandled
 * exception anywhere in the app records a crash report (LogRecorder)
 * and relaunches into CrashReportActivity showing a clear "App
 * Crashed — Check Logs" prompt, instead of the bare system "SparkyTube
 * keeps stopping" dialog with no way to see what actually went wrong.
 *
 * Chains to the previous default handler (usually the system's) after
 * recording + relaunching, rather than swallowing it -- keeps normal
 * OS-level crash reporting (e.g. for Play Store's crash stats, if this
 * is ever distributed that way) working exactly as before.
 */
class CrashHandler(private val app: Application) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            LogRecorder.recordCrash(app, thread, throwable)

            val intent = Intent(app, CrashReportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            // Always launch from the main thread, even if the crash
            // itself happened on a background thread (e.g. the yt-dlp
            // init thread in SparkyTubeApp.onCreate) -- starting the
            // recovery Activity from a background thread while
            // MainActivity's own onCreate might still be mid-flight on
            // the main thread was the likely cause of the black screen
            // bug: FLAG_ACTIVITY_CLEAR_TASK firing at the wrong moment
            // relative to MainActivity's own launch left a blank window
            // instead of cleanly showing the crash screen.
            //
            // Posting alone isn't enough though -- Process.killProcess()
            // below would fire before a posted Runnable ever gets a
            // chance to run, since it doesn't wait for the main looper.
            // A short sleep gives the post a real window to execute
            // first; startActivity() itself is what matters here, not
            // waiting for the Activity to fully render.
            if (Thread.currentThread() === android.os.Looper.getMainLooper().thread) {
                app.startActivity(intent)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    app.startActivity(intent)
                }
                Thread.sleep(400)
            }
        } catch (e: Exception) {
            // If even the crash-handling itself fails, fall through to
            // the default handler below rather than getting stuck.
        }

        // Let the previous handler (system default) still run so normal
        // crash semantics (process death, any OS-level crash reporting)
        // aren't broken by this -- but kill the process ourselves right
        // after so it doesn't linger in a half-crashed state waiting on
        // the old handler, since CrashReportActivity above already
        // covers what the user needs to see.
        defaultHandler?.uncaughtException(thread, throwable)
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }
}
