package dev.sparkynox.sparkytube.extractor.social

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import dev.sparkynox.sparkytube.logs.LogRecorder

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

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // a plain Chrome string: the login pages of these sites stall (blank, first frame only) on anything that looks embedded
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

            // the pages need a WebChromeClient for progress, dialogs and the sign in popups
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) ViewGroup.GONE else ViewGroup.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                    LogRecorder.d("SocialLogin", "start $u")
                }

                override fun onPageFinished(view: WebView, u: String?) {
                    LogRecorder.d("SocialLogin", "finished $u")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) LogRecorder.w("SocialLogin", "error ${error.errorCode} ${error.description} for ${request.url}")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) LogRecorder.w("SocialLogin", "http ${response.statusCode} for ${request.url}")
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val done = Button(this).apply {
            text = "Done, I'm signed in"
            setOnClickListener { CookieManager.getInstance().flush(); finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            fitsSystemWindows = true
            addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        // WebView adds "X-Requested-With: <package>" to every request and these sites turn embedded browsers away on it;
        // an empty value on the first request drops it (the YouTube sign in does the same)
        web.loadUrl(url, hashMapOf("X-Requested-With" to ""))
    }

    companion object {
        const val EXTRA_SITE = "site"
    }
}
