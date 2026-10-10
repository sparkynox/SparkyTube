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
# full: android_vr gives the adaptive 144p..1080p streams without needing a js
# runtime or po token, android gives the muxed 360p. yt-dlp asks the clients one
# after the other, so for the full list we run each client on its own thread and
# merge, that's roughly half the wait.
# if 1080p doesn't show up, this client list is the thing to change
_CLIENTS = ["android_vr", "android"]


def _one_client(client, url):
    # fresh instances per call, a YoutubeDL object isn't safe to share between threads
    try:
        return _make([client], _FAST).extract_info(url, download=False)
    except Exception:
        return _make([client], _SAFE).extract_info(url, download=False)


def _resolve_full(url):
    from concurrent.futures import ThreadPoolExecutor
    infos = []
    err = None
    with ThreadPoolExecutor(max_workers=len(_CLIENTS)) as pool:
        futs = [pool.submit(_one_client, c, url) for c in _CLIENTS]
        for f in futs:
            try:
                i = f.result()
                if i and i.get("formats"):
                    infos.append(i)
            except Exception as e:
                err = e
    if not infos:
        raise err or ValueError("no formats")
    base = infos[0]
    seen = set(f.get("format_id") for f in base["formats"])
    for other in infos[1:]:
        for f in other["formats"]:
            if f.get("format_id") not in seen:
                base["formats"].append(f)
                seen.add(f.get("format_id"))
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
