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

    // these need the permission in plugin.json: toast, video, download, open_url
    fun toast(message: String)
    fun videoInfoJson(): String
    fun downloadCurrent()
    fun openUrl(url: String)

    // always allowed
    fun log(message: String)
    fun storageGet(key: String): String?
    fun storageSet(key: String, value: String): Boolean
}
