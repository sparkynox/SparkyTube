/*
 * SparkyTube content-script equivalent.
 * Runs continuously inside the WebView page context (injected via
 * evaluateJavascript on every page load + SPA navigation).
 *
 * Responsibilities:
 *   1. Apply the always-on CSS layer (backup, in case the native-injected
 *      <style> tag from MainActivity hasn't landed yet on a fast page swap).
 *   2. Watch for the <video> element YouTube creates for playback and
 *      report its state to the native side via SparkyBridge (secondary
 *      trigger -- MainActivity's URL polling is the primary one).
 *   3. Backstop: if an ad slot ever slips past network-level blocking,
 *      mute AND visually hide it (not just mute) so nothing shows.
 */
(function () {
  if (window.__sparkyTubeInit) return;
  window.__sparkyTubeInit = true;

  var CHROME_HIDE_CSS = [
    'ytm-mobile-topbar-renderer','ytm-mobile-topbar-logo-renderer','#header',
    '.mobile-topbar-header','ytm-topbar-menu-button-renderer','ytm-searchbox',
    'tp-yt-app-toolbar','ytm-app-bar-view-model','.ytm-open-in-app-button',
    'ytm-open-in-app-button-renderer','ytm-you-are-here-renderer',
    'ytm-install-app-banner-renderer','ytm-app-promo-renderer',
    'ytm-pivot-bar-renderer','ytm-tabbed-page-header-renderer'
  ].join(',') + '{display:none !important; height:0 !important;}';

  var AD_HIDE_CSS = [
    'ytd-promoted-sparkles-web-renderer','ytd-display-ad-renderer',
    'ytd-in-feed-ad-layout-renderer','ytd-ad-slot-renderer',
    'ytd-companion-slot-renderer','ytd-statement-banner-renderer',
    'ytd-mealbar-promo-renderer','ytm-companion-slot-renderer',
    'ytm-promoted-sparkles-web-renderer','ytm-in-feed-ad-layout-renderer',
    '#masthead-ad','.video-ads.ytp-ad-module'
  ].join(',') + '{display:none !important; height:0 !important; visibility:hidden !important;}';

  function injectCssOnce() {
    if (document.getElementById('sparkytube-style')) return;
    var style = document.createElement('style');
    style.id = 'sparkytube-style';
    style.textContent =
      CHROME_HIDE_CSS + AD_HIDE_CSS +
      'html,body{background-color:#0A0A0A !important;}';
    document.documentElement.appendChild(style);
  }

  function isAdShowing() {
    var player = document.querySelector('.html5-video-player');
    return !!(player && player.classList.contains('ad-showing'));
  }

  function tryAdBackstop() {
    if (!isAdShowing()) return;
    // Try skip button first (legit -- same as a user tapping "skip ad")
    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern');
    if (skipBtn) {
      skipBtn.click();
      return;
    }
    // No skip yet -- mute AND visually hide the frame so nothing shows or
    // plays audibly while network-level blocking / skip catches up.
    var video = document.querySelector('video');
    if (video) {
      if (!video.muted) {
        video.muted = true;
        video.__sparkyForceMuted = true;
      }
      video.style.opacity = '0';
      video.__sparkyForceHidden = true;
    }
  }

  function restoreVideoIfAdGone(video) {
    if (!video) return;
    if (!isAdShowing()) {
      if (video.__sparkyForceMuted) {
        video.muted = false;
        video.__sparkyForceMuted = false;
      }
      if (video.__sparkyForceHidden) {
        video.style.opacity = '';
        video.__sparkyForceHidden = false;
      }
    }
  }

  function reportVideoState() {
    var video = document.querySelector('video');
    if (!video) return;

    restoreVideoIfAdGone(video);

    if (window.SparkyBridge && video.src) {
      var payload = {
        type: 'videoState',
        src: video.currentSrc || video.src,
        paused: video.paused,
        duration: video.duration || 0,
        currentTime: video.currentTime || 0,
        title: document.title.replace(' - YouTube', ''),
        adShowing: isAdShowing()
      };
      try {
        window.SparkyBridge.onVideoState(JSON.stringify(payload));
      } catch (e) { /* bridge not ready yet, ignore */ }
    }
  }

  // Continuous loop -- cheap checks, runs 24/7 while the WebView is alive.
  setInterval(function () {
    injectCssOnce();
    tryAdBackstop();
    reportVideoState();
  }, 500);

  // React fast to DOM changes (YouTube is a SPA, url/content swap without reload)
  var observer = new MutationObserver(function () {
    injectCssOnce();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });

  injectCssOnce();
})();
