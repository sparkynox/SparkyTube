// plugin runtime, injected before the plugins on every youtube page load.
// plugins only ever see the small `sparky` object from api(), nothing else of the app.
(function () {
  if (window.__sparkyPlugins) return;

  var native = window.SparkyPlugins;           // java bridge, one call() door
  var loaded = {};                             // id -> true, so a re-injection doesn't run a plugin twice
  var listeners = {};                          // event -> [{id, fn}]
  var lastUrl = location.href, lastVideo = videoOf(location.href);

  function videoOf(u) {
    try { return new URL(u, location.href).searchParams.get("v") || ""; } catch (e) { return ""; }
  }

  function emit(evt, data) {
    (listeners[evt] || []).slice().forEach(function (l) {
      try { l.fn(data); } catch (e) { report(l.id, e); }
    });
  }

  function report(id, e) {
    try { if (native) native.call(id, tokens[id] || "", "log", "error: " + (e && e.message ? e.message : e), ""); } catch (x) {}
  }

  var tokens = {};

  function api(id, token, meta, css) {
    if (!native || loaded[id]) return null;
    loaded[id] = true;
    tokens[id] = token;

    function call(method, a, b) {
      try { return native.call(id, token, method, a == null ? "" : String(a), b == null ? "" : String(b)); }
      catch (e) { return ""; }
    }

    if (css) addStyle(css);

    function addStyle(text) {
      var el = document.createElement("style");
      el.setAttribute("data-sparky-plugin", id);
      el.textContent = text;
      (document.head || document.documentElement).appendChild(el);
      return function () { if (el.parentNode) el.parentNode.removeChild(el); };
    }

    var s = {
      id: id,
      meta: meta,
      on: function (evt, fn) {
        if (typeof fn !== "function") return function () {};
        var l = { id: id, fn: fn };
        (listeners[evt] = listeners[evt] || []).push(l);
        return function () {
          var a = listeners[evt] || [], i = a.indexOf(l);
          if (i >= 0) a.splice(i, 1);
        };
      },
      ready: function (fn) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", function () { try { fn(); } catch (e) { report(id, e); } });
        else try { fn(); } catch (e) { report(id, e); }
      },
      addStyle: addStyle,
      storage: {
        get: function (key, def) {
          var v = call("storageGet", key);
          if (v === "") return def === undefined ? null : def;
          try { return JSON.parse(v); } catch (e) { return def === undefined ? null : def; }
        },
        set: function (key, value) { return call("storageSet", key, JSON.stringify(value)) === "1"; },
        remove: function (key) { call("storageRemove", key); }
      },
      toast: function (msg) { call("toast", msg); },
      log: function () { call("log", [].slice.call(arguments).join(" ")); },
      openUrl: function (url) { return call("openUrl", url) === "1"; },
      video: {
        info: function () {
          var v = call("videoInfo");
          try { return v ? JSON.parse(v) : null; } catch (e) { return null; }
        },
        id: function () { return videoOf(location.href); },
        download: function () { return call("download") === "1"; }
      },
      __error: function (e) { report(id, e); }
    };
    return s;
  }

  // page events: navigation + video change (youtube is a single page app)
  function checkNav() {
    var u = location.href;
    if (u === lastUrl) return;
    lastUrl = u;
    emit("navigate", { url: u });
    var v = videoOf(u);
    if (v !== lastVideo) {
      lastVideo = v;
      emit("videochange", { videoId: v });
    }
  }
  window.addEventListener("yt-navigate-finish", checkNav);
  window.addEventListener("popstate", checkNav);
  setInterval(checkNav, 700);

  window.__sparkyPlugins = { api: api, emit: emit, _v: 1 };
})();
