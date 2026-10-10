package dev.sparkynox.sparkytube.update

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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.databinding.ActivityHtmlSettingsBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// update + messages screen, the looks are in assets/update.html
class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
    }

    private lateinit var binding: ActivityHtmlSettingsBinding

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
            addJavascriptInterface(Bridge(), "SparkyUpdate")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/update.html")
        }

        // look for a new version as soon as the screen opens
        lifecycleScope.launch { UpdateManager.checkNow(this@UpdateActivity) }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun isNight(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @JavascriptInterface
        fun initialTab(): String = intent?.getStringExtra(EXTRA_TAB) ?: "update"

        @JavascriptInterface
        fun state(): String {
            val m = UpdateManager
            val j = JSONObject()
            j.put("currentName", m.installedName(this@UpdateActivity))
            j.put("currentCode", m.installedCode(this@UpdateActivity))
            j.put("phase", m.phase.name)
            j.put("checked", m.lastCheckOk)
            j.put("hasUpdate", m.hasUpdate(this@UpdateActivity))
            j.put("error", m.error)
            j.put("downloaded", m.downloaded)
            j.put("total", m.total)
            j.put("speed", m.speed)
            val l = m.latest
            if (l != null) {
                j.put("latest", JSONObject()
                    .put("name", l.versionName)
                    .put("code", l.versionCode)
                    .put("changelog", l.changelog)
                    .put("date", l.publishedAt)
                    .put("size", l.size)
                    .put("mandatory", l.mandatory))
            }
            val read = m.lastReadId(this@UpdateActivity)
            val arr = JSONArray()
            m.messages.forEach {
                arr.put(JSONObject()
                    .put("id", it.id).put("title", it.title).put("body", it.body)
                    .put("type", it.type).put("link", it.link).put("date", it.createdAt)
                    .put("unread", it.id > read))
            }
            j.put("messages", arr)
            return j.toString()
        }

        @JavascriptInterface
        fun check() {
            lifecycleScope.launch { UpdateManager.checkNow(this@UpdateActivity) }
        }

        @JavascriptInterface
        fun update() {
            runOnUiThread { UpdateManager.startDownload(this@UpdateActivity) }
        }

        @JavascriptInterface
        fun install() {
            runOnUiThread { UpdateManager.install(this@UpdateActivity) }
        }

        @JavascriptInterface
        fun markRead() {
            UpdateManager.markAllRead(this@UpdateActivity)
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
