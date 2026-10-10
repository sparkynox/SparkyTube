import json
import yt_dlp

_common = {
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
    "socket_timeout": 6,
    # a dead request should fail fast so the NewPipe fallback can take over,
    # the default retries are what made a bad extraction feel like a hang
    "extractor_retries": 1,
}


def _make(clients, skip):
    opts = dict(_common)
    opts["extractor_args"] = {
        "youtube": {
            "player_client": clients,
            "player_skip": skip,
            "skip": ["translated_subs"],
        }
    }
    return yt_dlp.YoutubeDL(opts)


# fast = don't download the watch page and don't do the extra "next" request.
# those two are the heavy part of an extraction and we only need the player
# response (formats, title, duration, description).
# safe = the setup that was already working, used if fast errors out or comes
# back without formats.
_FAST = ["webpage", "configs", "initial_data"]
_SAFE = ["configs"]

# data saver: android client only, ends up at 360p
_saver = (_make(["android"], _FAST), _make(["android"], _SAFE))
# full: android_vr (adaptive streams) + android (muxed 360p). each client gets its own
# warm instance built once at import, and the two run on separate threads so the
# wait is the slower one instead of the sum. building a YoutubeDL per call was what
# made this slow, it re-creates every extractor each time.
# if 1080p doesn't show up, the log line "diag" says which client gave what
import threading

_CLIENTS = ["android_vr", "android"]
_inst = {c: (_make([c], _FAST), _make([c], _SAFE), threading.Lock()) for c in _CLIENTS}


def _one_client(client, url):
    fast, safe, lock = _inst[client]
    with lock:  # one extraction at a time per instance, yt-dlp objects aren't thread-safe
        try:
            return fast.extract_info(url, download=False), ""
        except Exception as e:
            err = str(e).replace('"', "'").replace("\n", " ")[:90]
            # only the main client gets the slower retry, the other one is a bonus
            if client != "android":
                return None, err
        try:
            return safe.extract_info(url, download=False), ""
        except Exception as e:
            return None, str(e).replace('"', "'").replace("\n", " ")[:90]


def _max_height(info):
    best = 0
    for f in info.get("formats") or []:
        if f.get("url") and f.get("height"):
            best = max(best, int(f["height"]))
    return best


def _resolve_full(url):
    from concurrent.futures import ThreadPoolExecutor
    results = {}
    with ThreadPoolExecutor(max_workers=len(_CLIENTS)) as pool:
        futs = {c: pool.submit(_one_client, c, url) for c in _CLIENTS}
        for c, f in futs.items():
            results[c] = f.result()

    diag = []
    infos = []
    for c in _CLIENTS:
        info, err = results[c]
        if info and info.get("formats"):
            infos.append(info)
            diag.append("%s ok %d fmts max %dp" % (c, len(info["formats"]), _max_height(info)))
        else:
            diag.append("%s failed: %s" % (c, err or "no formats"))
    if not infos:
        raise ValueError("; ".join(diag))

    base = infos[0]
    seen = set(f.get("format_id") for f in base["formats"])
    for other in infos[1:]:
        for f in other["formats"]:
            if f.get("format_id") not in seen:
                base["formats"].append(f)
                seen.add(f.get("format_id"))
    base["_sparky_diag"] = " | ".join(diag)
    return base


def resolve_json(video_id, data_saver=True):
    url = "https://www.youtube.com/watch?v=" + video_id
    if not data_saver:
        info = _resolve_full(url)
        info["_sparky_path"] = "parallel"
        return json.dumps(info, default=str)

    fast, safe = _saver
    path = "fast"
    try:
        info = fast.extract_info(url, download=False)
        if not info or not info.get("formats"):
            raise ValueError("no formats")
    except Exception:
        path = "safe"
        info = safe.extract_info(url, download=False)
    info["_sparky_path"] = path
    return json.dumps(info, default=str)
