package dev.sparkynox.sparkytube.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.logs.LogRecorder
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Google sign-in in a WebView, same approach as LunoMusic's LoginScreen: log in, wait until the
 * SAPISID cookie exists, then read visitorData / datasync id / client version out of the page's
 * ytcfg and store everything in [Session]. Returns RESULT_OK when done.
 */
class LoginActivity : AppCompatActivity() {

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = getColor(R.color.bg_root)
        val fg = getColor(R.color.text_primary)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        bar.addView(TextView(this).apply {
            text = "Sign in to YouTube"
            setTextColor(fg)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        bar.addView(TextView(this).apply {
            text = "Close"
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            // Fixed: Overriding with a complete, modern Chrome User-Agent string.
            // Removing "; wv" is no longer enough to bypass Google's strict environment verification.
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

            // start from a clean jar so the account picker always shows
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (done || url == null) return
                    if (url.startsWith("https://m.youtube.com") || url.startsWith("https://www.youtube.com")) poll(view, url, 0)
                }
            }
            
            // Fixed: Added extra headers to drop the X-Requested-With header, reducing WebView fingerprint tracing.
            val extraHeaders = HashMap<String, String>().apply {
                put("X-Requested-With", "")
            }
            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F", extraHeaders)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    // ytcfg is not always ready right when the page finishes, so retry a few times
    private fun poll(view: WebView, url: String, tries: Int) {
        val cookie = CookieManager.getInstance().getCookie(url)
        if (cookie == null || !cookie.contains("SAPISID=")) return

        view.evaluateJavascript(GET_CONFIG) { raw ->
            val cfg = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            if (cfg == null || cfg.optString("v").isEmpty()) {
                if (tries < 8) view.postDelayed({ if (!done) poll(view, url, tries + 1) }, 700)
                else LogRecorder.w("Login", "signed in but ytcfg never had visitor data")
                return@evaluateJavascript
            }
            if (done) return@evaluateJavascript
            done = true
            Session.save(
                cookie = cookie,
                visitor = cfg.optString("v"),
                sync = cfg.optString("d").substringBefore("||"),
                clientVersion = cfg.optString("c"),
            )
            CookieManager.getInstance().flush()
            LogRecorder.i("Login", "login finished, session saved (client ${cfg.optString("c")})")
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private companion object {
        const val GET_CONFIG =
            "(function(){try{var g=function(k){try{return window.ytcfg.get(k)}catch(e){return null}};" +
                "return JSON.stringify({v:g('VISITOR_DATA')||'',d:g('DATASYNC_ID')||'',c:g('INNERTUBE_CLIENT_VERSION')||''});}" +
                "catch(e){return '';}})()"
    }
}
