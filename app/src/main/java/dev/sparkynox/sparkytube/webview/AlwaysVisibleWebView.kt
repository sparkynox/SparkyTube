package dev.sparkynox.sparkytube.webview

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that always reports itself as VISIBLE to the Android framework,
 * regardless of what the Activity/Window visibility actually is.
 *
 * Why this exists: overriding document.hidden/visibilityState in JS
 * (see injected.js) only fakes what page JavaScript *reads* — it does
 * NOT change Chromium's own internal page-visibility signal, which lives
 * a layer below JS and is driven directly by Android's View visibility
 * callbacks (onWindowVisibilityChanged / onVisibilityChanged). That
 * internal signal is what actually throttles IntersectionObserver
 * callbacks, requestAnimationFrame, and lazy-loaded content — which is
 * why YouTube's related-videos list (and therefore autoplay + pre-caching)
 * kept failing in the background even after the JS-level override: the
 * JS lie didn't reach the engine layer that was actually gating rendering.
 *
 * Forcing onWindowVisibilityChanged/onVisibilityChanged to always report
 * VISIBLE closes that gap — Chromium believes the page is on-screen and
 * fully active 24/7, exactly as if the app were always in the foreground.
 */
class AlwaysVisibleWebView(context: Context) : WebView(context) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Always tell Chromium the window is visible, no matter what
        // Android actually passed in (e.g. GONE when the Activity backgrounds).
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    /**
     * Public entry point for MainActivity to re-assert the always-visible
     * state (e.g. from onWindowFocusChanged). onWindowVisibilityChanged
     * itself is protected on View/WebView and can't be called directly
     * from outside the class — this just re-invokes the override above.
     */
    fun forceVisible() {
        onWindowVisibilityChanged(View.VISIBLE)
    }
}
