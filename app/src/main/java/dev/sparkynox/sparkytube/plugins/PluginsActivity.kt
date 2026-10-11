package dev.sparkynox.sparkytube.plugins

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.databinding.ActivityHtmlSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// plugin manager screen, the looks are in assets/plugins.html
class PluginsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHtmlSettingsBinding

    @Volatile private var busy = false
    @Volatile private var message = ""
    @Volatile private var messageOk = true
    @Volatile private var messageSeq = 0

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runInstall { PluginStore.installAuto(this, bytes(uri)) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHtmlSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        binding.htmlSettingsWeb.apply {
            setBackgroundColor(getColor(R.color.bg_root))
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge(), "SparkyPluginsUi")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/plugins.html")
        }
    }

    private fun bytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Couldn't open the file")
        input.use {
            val data = it.readBytes()
            if (data.size > 2 * 1024 * 1024) throw IllegalArgumentException("File is too big")
            return data
        }
    }

    // runs an install off the main thread and leaves the result in `message` for the page
    private fun runInstall(work: () -> PluginStore.Plugin) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { work() }
                message = "Installed ${p.name} ${p.version}"
                messageOk = true
            } catch (e: Exception) {
                message = e.message ?: "Install failed"
                messageOk = false
            }
            messageSeq++
            busy = false
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun isNight(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @JavascriptInterface
        fun state(): String {
            val arr = JSONArray()
            PluginStore.list(this@PluginsActivity).forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("name", it.name).put("version", it.version)
                        .put("author", it.author).put("description", it.description)
                        .put("permissions", JSONArray(it.permissions)).put("enabled", it.enabled)
                )
            }
            return JSONObject()
                .put("plugins", arr).put("busy", busy)
                .put("message", message).put("ok", messageOk).put("seq", messageSeq)
                .toString()
        }

        @JavascriptInterface
        fun setEnabled(id: String, on: Boolean) = PluginStore.setEnabled(this@PluginsActivity, id, on)

        @JavascriptInterface
        fun remove(id: String) = PluginStore.remove(this@PluginsActivity, id)

        @JavascriptInterface
        fun installCode(code: String) {
            runInstall { PluginStore.install(this@PluginsActivity, PluginStore.parseUserscript(code)) }
        }

        @JavascriptInterface
        fun installUrl(url: String) {
            runInstall { PluginStore.installFromUrl(this@PluginsActivity, url.trim()) }
        }

        @JavascriptInterface
        fun pickFile() {
            runOnUiThread { filePicker.launch(arrayOf("*/*")) }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            if (!url.startsWith("http")) return
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }
}
