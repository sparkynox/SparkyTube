package dev.sparkynox.sparkytube.plugins

import android.content.Context
import android.webkit.JavascriptInterface
import dev.sparkynox.sparkytube.logs.LogRecorder

// the one door plugins have into the app. every call carries the plugin id and its
// private token (only the injected wrapper knows it) and the permission is checked here
class PluginBridge(private val context: Context, private val host: Host) {

    interface Host {
        fun toast(message: String)
        fun videoInfoJson(): String
        fun downloadCurrent()
        fun openUrl(url: String)
    }

    @JavascriptInterface
    fun call(id: String, token: String, method: String, a: String, b: String): String {
        if (!PluginStore.verify(context, id, token)) return ""

        fun allowed(perm: String): Boolean {
            val ok = PluginStore.hasPermission(context, id, perm)
            if (!ok) LogRecorder.i("Plugin", "$id tried $method without the \"$perm\" permission")
            return ok
        }

        return when (method) {
            "storageGet" -> PluginStore.dataGet(context, id, a) ?: ""
            "storageSet" -> if (PluginStore.dataSet(context, id, a, b)) "1" else ""
            "storageRemove" -> { PluginStore.dataRemove(context, id, a); "1" }
            "log" -> { LogRecorder.i("Plugin", "$id: ${a.take(300)}"); "1" }
            "toast" -> if (allowed("toast")) { host.toast(a.take(200)); "1" } else ""
            "videoInfo" -> if (allowed("video")) host.videoInfoJson() else ""
            "download" -> if (allowed("download")) { host.downloadCurrent(); "1" } else ""
            "openUrl" -> if (allowed("open_url") && a.startsWith("http")) { host.openUrl(a); "1" } else ""
            else -> ""
        }
    }
}
