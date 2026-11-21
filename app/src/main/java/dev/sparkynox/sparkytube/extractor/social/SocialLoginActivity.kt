package dev.sparkynox.sparkytube.extractor.social

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Sign in to one of the sites in a WebView so stories and posts only your account can see can be downloaded.
 * The cookies stay in the app's WebView store and are only used for requests to that same site.
 */
class SocialLoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = intent.getStringExtra(EXTRA_SITE).orEmpty()
        val url = when (site.lowercase()) {
            "facebook" -> "https://m.facebook.com/login"
            "x", "twitter" -> "https://x.com/i/flow/login"
            "tiktok" -> "https://www.tiktok.com/login"
            else -> "https://www.instagram.com/accounts/login/"
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = SocialHttp.UA_MOBILE
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val done = Button(this).apply {
            text = "Done, I'm signed in"
            setOnClickListener { CookieManager.getInstance().flush(); finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(url)
    }

    companion object {
        const val EXTRA_SITE = "site"
    }
}
