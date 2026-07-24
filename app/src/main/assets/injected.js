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
 *   5. Override the Page Visibility API (document.hidden/visibilityState)
 *      so it always reports "visible" -- Chromium sets document.hidden=true
 *      the moment the Activity backgrounds, REGARDLESS of whether JS timers
 *      are still running. YouTube's related-videos list (which autoplay and
 *      pre-caching both depend on) is lazy-loaded behind an
 *      IntersectionObserver/visibility check, so without this override that
 *      list silently never populates while minimized or on the lock screen
 *      -- exactly the "no suggestions, video loops" symptom.
 */
(function () {
  if (window.__sparkyTubeInit) return;
  window.__sparkyTubeInit = true;

  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    // Stop the visibilitychange event itself from telling page JS otherwise.
    document.addEventListener('visibilitychange', function (e) {
      e.stopImmediatePropagation();
    }, true);
  } catch (e) { /* if the browser won't let us redefine these, we just fall back to default behavior */ }

  var CHROME_HIDE_CSS = [
    'ytm-mobile-topbar-renderer','ytm-mobile-topbar-logo-renderer','#header',
    '.mobile-topbar-header','ytm-topbar-menu-button-renderer','ytm-searchbox',
    'tp-yt-app-toolbar','ytm-app-bar-view-model','.ytm-open-in-app-button',
    'ytm-open-in-app-button-renderer','ytm-you-are-here-renderer',
    'ytm-install-app-banner-renderer','ytm-app-promo-renderer',
    'ytm-pivot-bar-renderer','ytm-tabbed-page-header-renderer',
    'ytm-feed-filter-chip-bar-renderer',
    'ytm-chip-cloud-renderer','ytm-chip-cloud-chip-renderer',
    'yt-chip-cloud-renderer','yt-chip-cloud-chip-renderer'
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

  // Text-based backstop for feed-level "Sponsored" ad cards. CSS renderer-tag
  // selectors (AD_HIDE_CSS above) break whenever YouTube renames a
  // component; walking up from the visible "Sponsored" label text to its
  // containing feed-item card is more resilient since that label is what
  // users (and ad policy) actually require to stay visible-or-removed,
  // not a specific tag name.
  function hideSponsoredCards() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      var text = node.nodeValue;
      if (!text || text.indexOf('Sponsored') === -1) continue;
      var el = node.parentElement;
      // Walk up to the nearest feed-item-shaped ancestor (a handful of
      // levels is enough on YouTube's mobile DOM) and hide that whole card.
      var card = el;
      for (var i = 0; i < 6 && card; i++) {
        var tag = card.tagName ? card.tagName.toLowerCase() : '';
        if (tag.indexOf('rich-item') !== -1 || tag.indexOf('renderer') !== -1) {
          card.style.display = 'none';
          card.style.height = '0';
          break;
        }
        card = card.parentElement;
      }
    }
  }

  function isAdShowing() {
    var player = document.querySelector('.html5-video-player');
    return !!(player && player.classList.contains('ad-showing'));
  }

  // The background <video> element must ALWAYS stay muted -- it plays
  // continuously now (for real watch-history tracking, see
  // disableNativeAutoplay's doc comment), not just paused as before. The
  // old restoreVideoIfAdGone() logic used to un-mute the video once an ad
  // ended, which made sense back when muting was only a temporary ad
  // countermeasure -- but now that this player runs 24/7 in the
  // background, un-muting it after an ad is exactly backwards: any ad
  // that manages to start playing (even briefly, before the skip button
  // appears) would audibly play until the next un-mute-undo tick. This
  // enforces muted=true unconditionally on every poll tick instead.
  function enforceAlwaysMuted(video) {
    if (!video) return;
    if (!video.muted) video.muted = true;
    if (video.volume !== 0) video.volume = 0;
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
      video.muted = true;
      video.style.opacity = '0';
      video.__sparkyForceHidden = true;
    }
  }

  function restoreVideoIfAdGone(video) {
    if (!video) return;
    if (!isAdShowing() && video.__sparkyForceHidden) {
      video.style.opacity = '';
      video.__sparkyForceHidden = false;
    }
  }

  // Mix/playlist "next track" resolution that survives the user closing
  // the playlist panel to read comments etc. Reading the panel DOM live
  // (old approach) broke the moment the panel wasn't open, since YouTube
  // only keeps ytm-playlist-panel-video-renderer rows mounted while the
  // panel itself is expanded -- collapsed panel meant findMixNextHref()
  // found nothing and silently fell through to a homepage-style related
  // video instead of the next Mix track. Fix: snapshot the full ordered
  // track list (and the list= id it belongs to) into sessionStorage the
  // moment the panel IS present in the DOM, then always resolve "next"
  // from that cached list keyed by list id -- independent of whether the
  // panel is currently open, closed, or scrolled off.
  var MIX_CACHE_KEY = 'sparkytube_mix_cache';

  function getListIdFromUrl(url) {
    var match = (url || window.location.href).match(/[?&]list=([a-zA-Z0-9_-]+)/);
    return match ? match[1] : null;
  }

  function snapshotMixPanelIfPresent() {
    var panel = document.querySelector('ytm-playlist-panel-renderer');
    if (!panel) return;
    var listId = getListIdFromUrl();
    if (!listId) return;

    var rows = panel.querySelectorAll('ytm-playlist-panel-video-renderer');
    if (!rows.length) return;

    var ids = [];
    for (var i = 0; i < rows.length; i++) {
      var link = rows[i].querySelector('a');
      var id = link ? extractVideoIdFromHref(link.href) : null;
      if (id) ids.push(id);
    }
    if (!ids.length) return;

    try {
      sessionStorage.setItem(MIX_CACHE_KEY, JSON.stringify({ listId: listId, ids: ids }));
    } catch (e) { /* sessionStorage unavailable -- Mix autoplay just falls back to related videos */ }
  }

  // Returns the next video ID in the currently-playing Mix, using the
  // cached ordered list keyed by the URL's list= id (works whether or not
  // the panel is currently expanded), or null if there's no cache for
  // this Mix yet or the current video isn't found in it (e.g. very first
  // load, before any panel snapshot happened -- __sparkyPlayNext falls
  // back to the live panel/related-video checks in that case).
  function findMixNextVideoId() {
    var listId = getListIdFromUrl();
    if (!listId) return null;
    var cached;
    try {
      cached = JSON.parse(sessionStorage.getItem(MIX_CACHE_KEY) || 'null');
    } catch (e) { return null; }
    if (!cached || cached.listId !== listId) return null;

    var currentId = (window.location.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/) || [])[1];
    var idx = cached.ids.indexOf(currentId);
    if (idx === -1 || idx === cached.ids.length - 1) return null;
    return cached.ids[idx + 1];
  }

  // Live-DOM fallback for the very first video of a Mix, before any
  // snapshot exists yet -- same logic as before, just demoted to a
  // fallback rather than the primary path.
  function findMixNextHref() {
    var panel = document.querySelector('ytm-playlist-panel-renderer');
    if (!panel) return null;
    var current = panel.querySelector(
      'ytm-playlist-panel-video-renderer[selected], ' +
      'ytm-playlist-panel-video-renderer.selected, ' +
      'ytm-playlist-panel-video-renderer[aria-selected="true"]'
    );
    var nextRow = current
      ? current.nextElementSibling
      : panel.querySelector('ytm-playlist-panel-video-renderer');
    while (nextRow && nextRow.tagName !== 'YTM-PLAYLIST-PANEL-VIDEO-RENDERER') {
      nextRow = nextRow.nextElementSibling;
    }
    var link = nextRow ? nextRow.querySelector('a') : null;
    return link ? link.href : null;
  }

  // Called from native (MainActivity) when ExoPlayer finishes the current
  // video, to advance to the next one the same way a real user would:
  // click YouTube's own autoplay "up next" card, the next track in a
  // Mix/playlist (from cache, so it works even with the panel closed),
  // or the first related video (in that priority order).
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
    var mixNextId = findMixNextVideoId();
    if (mixNextId) {
      var listId = getListIdFromUrl();
      window.location.href = 'https://m.youtube.com/watch?v=' + mixNextId +
        (listId ? '&list=' + listId : '');
      return true;
    }
    var mixNextHref = findMixNextHref();
    if (mixNextHref) {
      window.location.href = mixNextHref;
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

    var fromMixCache = findMixNextVideoId();
    if (fromMixCache) return fromMixCache;

    var fromMixLive = extractVideoIdFromHref(findMixNextHref());
    if (fromMixLive) return fromMixLive;

    var related = document.querySelector(
      'ytm-compact-video-renderer a, ' +
      'ytm-video-with-context-renderer a'
    );
    return related ? extractVideoIdFromHref(related.href) : null;
  };

  // Prevents YouTube's own autoplay from independently navigating the
  // background HTML5 player to a different video than the one ExoPlayer
  // is currently showing. Without this, if the background player buffers
  // slightly faster/slower than ExoPlayer and reaches its own end first,
  // YouTube's native autoplay could jump it to a different next-video than
  // the one advanceToNextVideo() (triggered by ExoPlayer's real state)
  // would pick — leaving the two players out of sync. All navigation is
  // meant to go through the native side's explicit trigger only.
  function disableNativeAutoplay() {
    var video = document.querySelector('video');
    if (!video || video.__sparkyAutoplayGuarded) return;
    video.__sparkyAutoplayGuarded = true;
    video.addEventListener('ended', function () {
      // Restart the same video from the top rather than letting YouTube's
      // engine auto-advance — native ExoPlayer's real end-of-playback
      // event is what actually drives advanceToNextVideo(). The user
      // never sees/hears this since it's muted and hidden behind
      // ExoPlayer's visible overlay.
      video.currentTime = 0;
      video.play().catch(function () {});
    });
    // Instant reaction to any attempt to un-mute -- the 500ms poll cycle
    // alone left a brief audible window right as an ad started (YouTube's
    // player sometimes resets muted=false internally when a new ad break
    // begins), which is the "ad plays and isn't muted" bug this closes.
    video.addEventListener('volumechange', function () {
      if (!video.muted) video.muted = true;
    });
  }

  function isWatchPage() {
    return window.location.pathname.indexOf('/watch') === 0;
  }

  function reportVideoState() {
    var video = document.querySelector('video');
    if (!video) return;

    // Only mute on /watch pages -- that's the only place ExoPlayer's
    // overlay provides the actual audio the user hears, with this
    // background WebView player existing purely for watch-history
    // tracking. Shorts (/shorts/...) deliberately have no ExoPlayer
    // takeover (see extractVideoId's doc comment in MainActivity.kt), so
    // the WebView's own video element IS the real, user-facing playback
    // there and must stay audible.
    if (isWatchPage()) {
      enforceAlwaysMuted(video);
      restoreVideoIfAdGone(video);
      disableNativeAutoplay();
    }

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
    hideSponsoredCards();
    snapshotMixPanelIfPresent();
  }, 500);

  // React fast to DOM changes (YouTube is a SPA, url/content swap without reload)
  var observer = new MutationObserver(function () {
    injectCssOnce();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });

  injectCssOnce();
})();
