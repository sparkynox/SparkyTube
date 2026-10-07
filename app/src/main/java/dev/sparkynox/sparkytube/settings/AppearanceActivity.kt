package dev.sparkynox.sparkytube.settings

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.databinding.ActivityAppearanceBinding

// The theme picker itself is an HTML page (assets/appearance.html), the
// native side only stores the choice and flips AppCompat's night mode.
class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appearanceBackBtn.setOnClickListener { finish() }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        binding.appearanceWeb.apply {
            setBackgroundColor(getColor(R.color.bg_root))
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge(), "SparkyTheme")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/appearance.html")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun getMode(): String = SettingsPrefs.getThemeMode(this@AppearanceActivity).name.lowercase()

        @JavascriptInterface
        fun isNight(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @JavascriptInterface
        fun setMode(mode: String) {
            val picked = try {
                SettingsPrefs.ThemeMode.valueOf(mode.uppercase())
            } catch (e: IllegalArgumentException) {
                return
            }
            runOnUiThread {
                SettingsPrefs.setThemeMode(this@AppearanceActivity, picked)
                // flips night mode for the whole app, AppCompat recreates the open screens
                SettingsPrefs.applyThemeMode(this@AppearanceActivity)
            }
        }
    }
}
