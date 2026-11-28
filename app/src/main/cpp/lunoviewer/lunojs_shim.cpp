// the "browser" BotGuard expects. small on purpose: anything it touches that isn't here shows up in the log as "shim miss"
const char* LUNOJS_SHIM = R"LUNOJS(
(function (g) {
  var UA = g.__UA;
  var log = function (s) { __nativeLog(String(s)); };
  var noop = function () {};

  g.window = g; g.self = g; g.top = g; g.parent = g; g.frames = g; g.global = g;
  g.console = { log: log, info: log, warn: log, error: log, debug: log, trace: noop };

  // ---- timers
  g.setTimeout = function (fn, ms) {
    var a = [].slice.call(arguments, 2);
    return __nativeSetTimer(function () { fn.apply(g, a); }, +ms || 0, false);
  };
  g.setInterval = function (fn, ms) {
    var a = [].slice.call(arguments, 2);
    return __nativeSetTimer(function () { fn.apply(g, a); }, +ms || 0, true);
  };
  g.clearTimeout = __nativeClearTimer;
  g.clearInterval = __nativeClearTimer;
  g.setImmediate = function (f) { return g.setTimeout(f, 0); };
  g.queueMicrotask = function (f) { Promise.resolve().then(f); };
  g.requestAnimationFrame = function (f) { return g.setTimeout(function () { f(__nativeNow()); }, 16); };
  g.cancelAnimationFrame = __nativeClearTimer;
  g.requestIdleCallback = function (f) { return g.setTimeout(function () { f({ didTimeout: false, timeRemaining: function () { return 10; } }); }, 1); };
  g.cancelIdleCallback = __nativeClearTimer;

  // ---- base64
  var B = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = function (s) {
    s = String(s);
    var out = '';
    for (var i = 0; i < s.length; i += 3) {
      var a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
      if (a > 255 || b > 255 || c > 255) throw new Error('InvalidCharacterError');
      var n = (a << 16) | ((b || 0) << 8) | (c || 0);
      out += B[(n >> 18) & 63] + B[(n >> 12) & 63] +
        (i + 1 < s.length ? B[(n >> 6) & 63] : '=') + (i + 2 < s.length ? B[n & 63] : '=');
    }
    return out;
  };
  g.atob = function (s) {
    s = String(s).replace(/[\t\n\f\r ]+/g, '').replace(/=+$/, '');
    if (s.length % 4 === 1) throw new Error('InvalidCharacterError');
    var out = '', bits = 0, acc = 0;
    for (var i = 0; i < s.length; i++) {
      var v = B.indexOf(s[i]);
      if (v < 0) throw new Error('InvalidCharacterError');
      acc = (acc << 6) | v; bits += 6;
      if (bits >= 8) { bits -= 8; out += String.fromCharCode((acc >> bits) & 255); acc &= (1 << bits) - 1; }
    }
    return out;
  };

  // ---- text
  g.TextEncoder = function () { this.encoding = 'utf-8'; };
  g.TextEncoder.prototype.encode = function (str) {
    var s = unescape(encodeURIComponent(String(str === undefined ? '' : str)));
    var u = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
    return u;
  };
  g.TextDecoder = function () { this.encoding = 'utf-8'; };
  g.TextDecoder.prototype.decode = function (buf) {
    if (!buf) return '';
    var u = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    var s = '';
    for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
  };

  // ---- misc globals
  g.performance = {
    now: __nativeNow, timeOrigin: Date.now(), timing: { navigationStart: Date.now() },
    mark: noop, measure: noop, clearMarks: noop,
    getEntries: function () { return []; }, getEntriesByType: function () { return []; }, getEntriesByName: function () { return []; }
  };
  g.crypto = {
    getRandomValues: function (a) { for (var i = 0; i < a.length; i++) a[i] = __nativeRandom(); return a; },
    randomUUID: function () {
      return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = __nativeRandom() & 15; return (c === 'x' ? r : (r & 3) | 8).toString(16);
      });
    },
    subtle: {}
  };
  if (!g.Intl) {
    g.Intl = {
      DateTimeFormat: function () {
        return { resolvedOptions: function () { return { timeZone: 'UTC', locale: 'en-US', calendar: 'gregory', numberingSystem: 'latn' }; }, format: function (d) { return String(d); } };
      },
      NumberFormat: function () { return { format: function (n) { return String(n); }, resolvedOptions: function () { return { locale: 'en-US' }; } }; },
      Collator: function () { return { compare: function (a, b) { return a < b ? -1 : a > b ? 1 : 0; } }; }
    };
  }
  if (!Error.captureStackTrace) Error.captureStackTrace = function (o) { if (o && !o.stack) o.stack = ''; };
  if (Error.stackTraceLimit === undefined) Error.stackTraceLimit = 10;

  // ---- events
  function EventTarget() { this._l = {}; }
  EventTarget.prototype.addEventListener = function (t, f) { (this._l[t] = this._l[t] || []).push(f); };
  EventTarget.prototype.removeEventListener = function (t, f) { var a = this._l[t]; if (a) { var i = a.indexOf(f); if (i >= 0) a.splice(i, 1); } };
  EventTarget.prototype.dispatchEvent = function (e) { var a = this._l[e.type]; if (a) a.slice().forEach(function (f) { f.call(this, e); }, this); return true; };
  g.EventTarget = EventTarget;
  g.Event = function (type, init) { this.type = type; this.bubbles = !!(init && init.bubbles); this.cancelable = !!(init && init.cancelable); this.defaultPrevented = false; this.timeStamp = __nativeNow(); };
  g.Event.prototype.preventDefault = function () { this.defaultPrevented = true; };
  g.Event.prototype.stopPropagation = noop;
  g.CustomEvent = function (type, init) { g.Event.call(this, type, init); this.detail = init ? init.detail : null; };
  g.addEventListener = noop; g.removeEventListener = noop; g.dispatchEvent = function () { return true; };

  // ---- misses get logged once, so the log says what to add next
  var seen = {};
  function watch(name, o) {
    return new Proxy(o, {
      get: function (t, k, r) {
        if (typeof k === 'string' && !(k in t) && !seen[name + '.' + k]) { seen[name + '.' + k] = 1; log('shim miss: ' + name + '.' + k); }
        return Reflect.get(t, k, r);
      }
    });
  }

  // ---- dom, just enough to build elements and read nothing back
  function el(tag) {
    var e = new EventTarget();
    e.tagName = String(tag).toUpperCase(); e.nodeName = e.tagName; e.nodeType = 1;
    e.style = {}; e.children = []; e.childNodes = []; e.attributes = {}; e.dataset = {}; e.classList = { add: noop, remove: noop, contains: function () { return false; }, toggle: noop };
    e.innerHTML = ''; e.textContent = ''; e.innerText = ''; e.id = ''; e.className = ''; e.src = ''; e.href = '';
    e.offsetWidth = 0; e.offsetHeight = 0; e.clientWidth = 0; e.clientHeight = 0; e.scrollWidth = 0; e.scrollHeight = 0;
    e.parentNode = null;
    e.setAttribute = function (k, v) { e.attributes[k] = String(v); };
    e.getAttribute = function (k) { return k in e.attributes ? e.attributes[k] : null; };
    e.removeAttribute = function (k) { delete e.attributes[k]; };
    e.appendChild = function (c) { e.children.push(c); e.childNodes.push(c); c.parentNode = e; return c; };
    e.insertBefore = e.appendChild;
    e.removeChild = function (c) { var i = e.children.indexOf(c); if (i >= 0) { e.children.splice(i, 1); e.childNodes.splice(i, 1); } return c; };
    e.remove = noop; e.focus = noop; e.blur = noop; e.click = noop;
    e.cloneNode = function () { return el(tag); };
    e.getBoundingClientRect = function () { return { x: 0, y: 0, width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0 }; };
    e.getContext = function () { return null; };
    e.toDataURL = function () { return 'data:,'; };
    e.querySelector = function () { return null; };
    e.querySelectorAll = function () { return []; };
    e.getElementsByTagName = function () { return []; };
    return e;
  }
  var loc = {
    href: 'https://www.youtube.com/', origin: 'https://www.youtube.com', protocol: 'https:', host: 'www.youtube.com',
    hostname: 'www.youtube.com', pathname: '/', search: '', hash: '', port: '',
    toString: function () { return 'https://www.youtube.com/'; }, assign: noop, replace: noop, reload: noop
  };
  g.location = loc;
  var doc = new EventTarget();
  doc.createElement = el;
  doc.createElementNS = function (ns, t) { return el(t); };
  doc.createTextNode = function (t) { return { nodeType: 3, nodeValue: String(t), textContent: String(t) }; };
  doc.createEvent = function () { return new g.Event(''); };
  doc.body = el('body'); doc.documentElement = el('html'); doc.head = el('head');
  doc.cookie = ''; doc.referrer = ''; doc.title = ''; doc.domain = 'www.youtube.com'; doc.URL = loc.href; doc.documentURI = loc.href;
  doc.location = loc; doc.readyState = 'complete'; doc.hidden = false; doc.visibilityState = 'visible';
  doc.characterSet = 'UTF-8'; doc.charset = 'UTF-8'; doc.compatMode = 'CSS1Compat'; doc.contentType = 'text/html'; doc.nodeType = 9;
  doc.defaultView = g; doc.currentScript = null; doc.scripts = []; doc.forms = []; doc.images = []; doc.all = [];
  doc.getElementById = function () { return null; };
  doc.getElementsByTagName = function () { return []; };
  doc.getElementsByClassName = function () { return []; };
  doc.querySelector = function () { return null; };
  doc.querySelectorAll = function () { return []; };
  doc.hasFocus = function () { return true; };
  g.document = watch('document', doc);

  g.navigator = watch('navigator', {
    userAgent: UA, appVersion: UA.replace('Mozilla/', ''), appName: 'Netscape', appCodeName: 'Mozilla', product: 'Gecko', productSub: '20030107',
    platform: 'Win32', vendor: 'Google Inc.', vendorSub: '', language: 'en-US', languages: ['en-US', 'en'],
    hardwareConcurrency: 8, deviceMemory: 8, maxTouchPoints: 0, cookieEnabled: true, onLine: true, webdriver: false,
    plugins: [], mimeTypes: [], doNotTrack: null, pdfViewerEnabled: true,
    sendBeacon: function () { return true; }, javaEnabled: function () { return false; }
  });
  g.screen = watch('screen', {
    width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, availLeft: 0, availTop: 0, colorDepth: 24, pixelDepth: 24,
    orientation: { type: 'landscape-primary', angle: 0 }
  });
  g.innerWidth = 1280; g.innerHeight = 720; g.outerWidth = 1280; g.outerHeight = 800;
  g.screenX = 0; g.screenY = 0; g.pageXOffset = 0; g.pageYOffset = 0; g.scrollX = 0; g.scrollY = 0; g.devicePixelRatio = 1;
  g.isSecureContext = true; g.origin = loc.origin; g.name = '';
  g.getComputedStyle = function () { return {}; };
  g.matchMedia = function () { return { matches: false, media: '', addListener: noop, removeListener: noop, addEventListener: noop, removeEventListener: noop }; };
  g.postMessage = noop; g.open = function () { return null; }; g.close = noop; g.focus = noop; g.blur = noop; g.scrollTo = noop;

  function store() {
    var m = {};
    return {
      getItem: function (k) { return k in m ? m[k] : null; }, setItem: function (k, v) { m[k] = String(v); },
      removeItem: function (k) { delete m[k]; }, clear: function () { m = {}; }, key: function (i) { return Object.keys(m)[i] || null; },
      get length() { return Object.keys(m).length; }
    };
  }
  g.localStorage = store(); g.sessionStorage = store();
  g.history = { length: 1, state: null, pushState: noop, replaceState: noop, back: noop, forward: noop, go: noop };

  g.MutationObserver = function () { this.observe = noop; this.disconnect = noop; this.takeRecords = function () { return []; }; };
  g.ResizeObserver = g.MutationObserver; g.IntersectionObserver = g.MutationObserver;
  g.XMLHttpRequest = function () { this.open = noop; this.send = noop; this.setRequestHeader = noop; this.abort = noop; this.readyState = 0; this.status = 0; };
  g.fetch = function () { return Promise.reject(new TypeError('no network in this engine')); };

  log('shim ready');
})(globalThis);
)LUNOJS";
