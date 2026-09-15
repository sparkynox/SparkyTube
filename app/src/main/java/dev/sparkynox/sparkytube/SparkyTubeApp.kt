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

        // yt-dlp fallback (see extractor/YtDlpResolver.kt): only init if the
        // user has actually turned this on in Settings -- unpacking the
        // bundled Python/yt-dlp binaries on first run takes a moment, so
        // doing it here (background thread, app startup) means it's ready
        // by the time a video actually needs it instead of stalling
        // playback the first time the fallback fires.
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isYtDlpFallbackEnabled(this)) {
            Thread {
                dev.sparkynox.sparkytube.extractor.YtDlpResolver.init(this)
            }.start()
        }
    }
}
