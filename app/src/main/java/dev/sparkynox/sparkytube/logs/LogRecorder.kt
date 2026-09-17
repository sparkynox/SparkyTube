package dev.sparkynox.sparkytube.logs

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every log line the app writes for the current session (from
 * process start) to a plain text file under app-private storage, plus
 * keeps the last crash's stack trace in its own file so the "App
 * Crashed" dialog and the Logs screen can show it without needing to
 * grep the full session log.
 *
 * This does NOT hook into Logcat globally (no root/shell access needed,
 * and reading other apps' logs isn't possible past Android 4.1 anyway)
 * -- instead, call sites across the app funnel their own log lines
 * through LogRecorder.log() alongside (or instead of) android.util.Log,
 * so "logs" here means "everything SparkyTube itself chose to log" not
 * "raw system logcat".
 */
object LogRecorder {

    private const val SESSION_LOG_FILE = "sparky_session_log.txt"
    private const val CRASH_LOG_FILE = "sparky_last_crash.txt"

    // Ring-buffer-ish cap -- once the session log passes this size, the
    // oldest third is dropped rather than letting it grow unbounded for
    // a session that's been open a long time (background playback,
    // left running overnight, etc.)
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024 // 2MB

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    private val writeLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        // Fresh file per app launch -- previous session's log isn't
        // useful once a new session has started successfully, and
        // starting clean keeps MAX_LOG_SIZE_BYTES trimming simple.
        try {
            sessionLogFile(context)?.writeText(
                "=== SparkyTube session started ${timeFormat.format(Date())} ===\n"
            )
        } catch (e: Exception) {
            // Can't write logs -- nothing else to do about it, and
            // definitely shouldn't crash the app over logging itself.
        }
    }

    private fun sessionLogFile(context: Context): File? =
        try { File(context.filesDir, SESSION_LOG_FILE) } catch (e: Exception) { null }

    private fun crashLogFile(context: Context): File? =
        try { File(context.filesDir, CRASH_LOG_FILE) } catch (e: Exception) { null }

    /**
     * Appends one log line. tag/level follow the usual Log.d/e style so
     * call sites read naturally; level is just a short prefix here, not
     * routed to actual android.util.Log (callers that also want Logcat
     * output should call that separately, same as before -- this is
     * additive, not a replacement).
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val ctx = appContext ?: return
        val file = sessionLogFile(ctx) ?: return

        synchronized(writeLock) {
            try {
                val line = buildString {
                    append(timeFormat.format(Date()))
                    append(' ').append(level).append('/').append(tag).append(": ").append(message)
                    if (throwable != null) {
                        append('\n')
                        val sw = StringWriter()
                        throwable.printStackTrace(PrintWriter(sw))
                        append(sw.toString())
                    }
                    append('\n')
                }
                file.appendText(line)

                if (file.length() > MAX_LOG_SIZE_BYTES) {
                    trimLog(file)
                }
            } catch (e: Exception) {
                // Logging must never itself throw into the caller.
            }
        }
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)

    private fun trimLog(file: File) {
        try {
            val lines = file.readLines()
            val keepFrom = lines.size / 3
            file.writeText(lines.drop(keepFrom).joinToString("\n") + "\n")
        } catch (e: Exception) {
            // If trimming fails, worst case the file just keeps growing
            // until next app launch resets it -- not worth crashing over.
        }
    }

    /**
     * Full current session log text, for the Logs screen. Empty string
     * if nothing's been recorded yet or the file can't be read.
     */
    fun getSessionLog(context: Context): String =
        try { sessionLogFile(context)?.readText() ?: "" } catch (e: Exception) { "" }

    /**
     * The most recently recorded crash's stack trace, or null if the app
     * hasn't crashed since crash recording started (or the file was
     * cleared). Read by the crash dialog and the Logs screen.
     */
    fun getLastCrash(context: Context): String? =
        try {
            val f = crashLogFile(context)
            if (f != null && f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }

    fun clearLastCrash(context: Context) {
        try { crashLogFile(context)?.delete() } catch (e: Exception) { }
    }

    fun clearSessionLog(context: Context) {
        try { sessionLogFile(context)?.writeText("") } catch (e: Exception) { }
    }

    /**
     * Records a crash's full stack trace (plus a copy of everything
     * logged so far this session, since that context is often more
     * useful for figuring out what led up to the crash than the stack
     * trace alone) to CRASH_LOG_FILE. Called from the uncaught exception
     * handler right before the process actually dies -- see
     * SparkyTubeApp.kt.
     */
    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val report = buildString {
                append("=== SparkyTube crash ${timeFormat.format(Date())} ===\n")
                append("Thread: ${thread.name}\n\n")
                append(sw.toString())
                append("\n\n=== Session log leading up to crash ===\n")
                append(getSessionLog(context).takeLast(20_000)) // last ~20k chars is plenty of lead-up context
            }
            crashLogFile(context)?.writeText(report)
        } catch (e: Exception) {
            // If we can't even record the crash, there's nothing further
            // to do -- the default uncaught handler chain still runs
            // after this (see SparkyTubeApp.kt), so the crash itself is
            // still handled normally either way.
        }
    }
}
