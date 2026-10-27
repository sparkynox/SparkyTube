package dev.sparkynox.sparkytube.plugins.api

import android.content.Context

// base class for native plugins (dex/jar). everything here runs on the main thread,
// don't block in it. keep these signatures stable, plugins are compiled against them
abstract class SparkyPlugin {
    abstract fun onLoad(host: PluginHost)

    // name: play, pause, videoend, ... dataJson: json object as a string
    open fun onEvent(name: String, dataJson: String) {}

    open fun onUnload() {}
}

interface PluginHost {
    fun context(): Context

    // helpers, no permission needed for native plugins
    fun toast(message: String)
    fun videoInfoJson(): String
    fun downloadCurrent()
    fun openUrl(url: String)
    fun runOnUi(r: Runnable)
    fun evaluateJs(js: String)

    // replaces a piece of app text, null puts the original back. keys: home_greeting
    fun setUiText(key: String, value: String?)

    fun activity(): android.app.Activity
    fun webView(): android.webkit.WebView

    fun log(message: String)
    fun storageGet(key: String): String?
    fun storageSet(key: String, value: String): Boolean
}
