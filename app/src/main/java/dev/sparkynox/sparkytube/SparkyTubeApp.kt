package dev.sparkynox.sparkytube

import android.app.Application
import com.google.android.material.color.DynamicColors

class SparkyTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Logs (Settings > Logs): starts recording a fresh session log
        // immediately, and installs the crash handler so any uncaught
        // exception anywhere in the app gets a proper "App Crashed —
        // Check Logs" screen instead of the bare system dialog. Both
        // need to happen before anything else below has a chance to
        // throw, so this is first.
        dev.sparkynox.sparkytube.logs.LogRecorder.init(this)
        Thread.setDefaultUncaughtExceptionHandler(dev.sparkynox.sparkytube.logs.CrashHandler(this))

        // before any activity exists, so the very first screen already has the right colors
        dev.sparkynox.sparkytube.settings.SettingsPrefs.applyThemeMode(this)

        // Nothing else heavy here on purpose — AdBlockEngine loads its JSON
        // lazily the first time MainActivity spins up the WebView.

        // Dynamic Color (Android 12+ Material You): maps the system
        // wallpaper's palette onto colorPrimary/colorSecondary/etc at
        // runtime. Off by default -- SparkyTube has its own red brand
        // accent (see @color/accent in themes.xml) that most people
        // associate with the app, so this only applies if the user
        // explicitly opts in from Settings > Appearance. No-op below
        // API 31 or when the toggle is off.
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isDynamicColorEnabled(this)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // yt-dlp fallback (see extractor/PyYtDlp.kt): only init if the
        // user has actually turned this on in Settings. Chaquopy's Python
        // interpreter starts once here on a background thread and then
        // stays resident for the app's whole process lifetime -- unlike
        // the old youtubedl-android version, nothing here needs to run
        // again before a video resolve, there's no per-call subprocess
        // boot cost anymore.
        val settings = dev.sparkynox.sparkytube.settings.SettingsPrefs
        val ytDlpIsDefault = settings.getExtractorMethod(this) ==
            dev.sparkynox.sparkytube.settings.SettingsPrefs.ExtractorMethod.YT_DLP
        if (ytDlpIsDefault || settings.isYtDlpFallbackEnabled(this)) {
            Thread {
                dev.sparkynox.sparkytube.extractor.PyYtDlp.init(this)
            }.start()
        }
    }
}
