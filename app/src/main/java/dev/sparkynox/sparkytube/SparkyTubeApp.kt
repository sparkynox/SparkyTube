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
        // a native crash from last time gets turned into a normal crash report first
        dev.sparkynox.sparkytube.logs.NativeCrash.consume(this)
        dev.sparkynox.sparkytube.logs.LogRecorder.init(this)
        Thread.setDefaultUncaughtExceptionHandler(dev.sparkynox.sparkytube.logs.CrashHandler(this))
        dev.sparkynox.sparkytube.logs.NativeCrash.install(this)

        dev.sparkynox.sparkytube.net.RemoteConfig.init(this)
        dev.sparkynox.sparkytube.net.NetCache.init(this)
        dev.sparkynox.sparkytube.fx.LunoFx.init(this)

        // YouTube login state (cookie + visitor data), used by the home feed
        dev.sparkynox.sparkytube.auth.Session.init(this)
        dev.sparkynox.sparkytube.auth.AccountStore.init(this)
        dev.sparkynox.sparkytube.lunokit.glass.LunoGlass.init(this)
        dev.sparkynox.sparkytube.lunokit.ambient.AmbientPrefs.init(this)

        // before any activity exists, so the very first screen already has the right colors
        dev.sparkynox.sparkytube.settings.SettingsPrefs.applyThemeMode(this)

        // background check for new versions + developer messages (every ~6h, only on network)
        dev.sparkynox.sparkytube.update.UpdateManager.schedule(this)

        // refresh the ad/tracker lists in the background on app start (version.json check, tiny when unchanged)
        if (dev.sparkynox.sparkytube.settings.SettingsPrefs.isBlocklistAutoUpdateEnabled(this)) {
            dev.sparkynox.sparkytube.adblock.BlocklistUpdater.schedule(this)
        }

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

        // InnerTubeX: building the client is cheap, the player-config/cipher warm-up runs on its own
        // background scope. only when it can actually be used (selected method or fallback on)
        val settings = dev.sparkynox.sparkytube.settings.SettingsPrefs
        val itxSelected = settings.getExtractorMethod(this) ==
            dev.sparkynox.sparkytube.settings.SettingsPrefs.ExtractorMethod.INNERTUBEX
        if (itxSelected || settings.isInnerTubeXFallbackEnabled(this)) {
            Thread { dev.sparkynox.sparkytube.extractor.InnerTubeXResolver.init(this) }.start()
        }
    }
}
