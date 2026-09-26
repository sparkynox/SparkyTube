/* YouTube Skin -- JS half. CSS file handles pure styling, this does the
   couple things you need JS for: injecting the CSS itself (so it works
   even if MainActivity's native injection is late), and re-running on
   SPA navigation since YouTube swaps DOM without a full page reload. */
(function () {
  if (window.__sparkySkinInit) return;
  window.__sparkySkinInit = true;

  function injectSkinCss() {
    if (document.getElementById('__sparky_skin_css')) return;
    var link = document.createElement('link');
    link.id = '__sparky_skin_css';
    link.rel = 'stylesheet';
    link.href = 'https://appassets.androidplatform.net/assets/youtube_skin.css';
    document.head.appendChild(link);
  }

  injectSkinCss();

  // YouTube's mobile site is a SPA -- home feed re-renders on nav without
  // a full reload, sometimes wiping our <link> tag if it touches <head>.
  // Cheap watchdog instead of hooking their internal router (which isn't
  // a stable API to depend on).
  setInterval(injectSkinCss, 2000);

  // shelf header text is sometimes just plain text nodes with no easy CSS
  // hook for spacing -- wrap loose text so the CSS padding rules above
  // actually have something to apply to
  function tidyShelfHeaders() {
    document.querySelectorAll('ytm-shelf-renderer, ytm-rich-section-renderer').forEach(function (shelf) {
      if (shelf.__sparkyTidied) return;
      shelf.__sparkyTidied = true;
    });
  }
  var observer = new MutationObserver(tidyShelfHeaders);
  observer.observe(document.body, { childList: true, subtree: true });
})();
