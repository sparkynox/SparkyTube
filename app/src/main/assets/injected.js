/*
 * SparkyTube content-script equivalent.
 * Runs continuously inside the WebView page context (injected via
 * evaluateJavascript on every page load + SPA navigation).
 *
 * Responsibilities:
 *   1. Apply the always-on CSS layer (backup, in case the native-injected
 *      <style> tag from MainActivity hasn't landed yet on a fast page swap).
 *      Full styling lives in assets/injected.css -- MainActivity injects
 *      that file's contents directly, this is a lightweight fallback subset
 *      covering just the chrome-hiding + ad-hiding rules so nothing flashes
 *      unstyled before the native injection lands.
 *   2. Watch for the <video> element YouTube creates for playback and
 *      report its state to the native side via SparkyBridge (secondary
 *      trigger -- MainActivity's URL polling is the primary one).
 *   3. Backstop: if an ad slot ever slips past network-level blocking,
 *      mute AND visually hide it (not just mute) so nothing shows.
 *   4. Expose __sparkyPlayNext() so native can advance to the next video
 *      (autoplay/up-next click) when ExoPlayer finishes a video.
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

  // Collapses YouTube's ENTIRE player container (not just the <video> tag)
  // once native ExoPlayer takes over -- the container renders its own
  // poster/background frame independent of the video element's own
  // opacity, which was the leftover black/thumbnail gap under the
  // ExoPlayer overlay.
  var NATIVE_ACTIVE_CSS =
    'body.sparkytube-native-active .html5-video-player,' +
    'body.sparkytube-native-active #player-container-id,' +
    'body.sparkytube-native-active #player-container,' +
    'body.sparkytube-native-active ytd-player,' +
    'body.sparkytube-native-active ytm-inline-player-renderer,' +
    'body.sparkytube-native-active video' +
    '{opacity:0 !important; pointer-events:none !important; height:0 !important; min-height:0 !important; max-height:0 !important; overflow:hidden !important;}';

  function injectCssOnce() {
    if (document.getElementById('sparkytube-style')) return;
    var style = document.createElement('style');
    style.id = 'sparkytube-style';
    style.textContent =
      CHROME_HIDE_CSS + AD_HIDE_CSS + NATIVE_ACTIVE_CSS +
      'html,body{background-color:#0A0A0A !important;}';
    document.documentElement.appendChild(style);
  }

  function isAdShowing() {
    var player = document.querySelector('.html5-video-player');
    return !!(player && player.classList.contains('ad-showing'));
  }

  function tryAdBackstop() {
    if (!isAdShowing()) return;
    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern');
    if (skipBtn) {
      skipBtn.click();
      return;
    }
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

  // Called from native (MainActivity) when ExoPlayer finishes the current
  // video, to advance to the next one the same way a real user would:
  // click YouTube's own autoplay "up next" card or the first related video.
  window.__sparkyPlayNext = function () {
    var autoplayCard = document.querySelector(
      '.ytp-autonav-endscreen-upnext-container a, ' +
      '.ytp-videowall-still, ' +
      'a.ytp-next-button'
    );
    if (autoplayCard) {
      autoplayCard.click();
      return true;
    }
    var related = document.querySelector(
      'ytm-compact-video-renderer a, ' +
      'ytm-video-with-context-renderer a'
    );
    if (related && related.href) {
      window.location.href = related.href;
      return true;
    }
    return false;
  };

  // Predicts which video __sparkyPlayNext would advance to, WITHOUT
  // clicking/navigating -- used for pre-caching. Same selector priority
  // as __sparkyPlayNext so the prediction actually matches what autoplay
  // will pick. Returns an 11-char YouTube video ID, or null if nothing
  // resolvable yet (e.g. related list hasn't loaded).
  function extractVideoIdFromHref(href) {
    if (!href) return null;
    var watchMatch = href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
    if (watchMatch) return watchMatch[1];
    var shortsMatch = href.match(/\/shorts\/([a-zA-Z0-9_-]{11})/);
    if (shortsMatch) return shortsMatch[1];
    return null;
  }

  window.__sparkyPredictNextVideoId = function () {
    var autoplayCard = document.querySelector(
      '.ytp-autonav-endscreen-upnext-container a, ' +
      '.ytp-videowall-still, ' +
      'a.ytp-next-button'
    );
    var fromAutoplay = autoplayCard ? extractVideoIdFromHref(autoplayCard.href) : null;
    if (fromAutoplay) return fromAutoplay;

    var related = document.querySelector(
      'ytm-compact-video-renderer a, ' +
      'ytm-video-with-context-renderer a'
    );
    return related ? extractVideoIdFromHref(related.href) : null;
  };

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
