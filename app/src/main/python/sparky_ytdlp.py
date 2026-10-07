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
# runtime or po token, android stays in for the muxed 360p.
# if 1080p doesn't show up, this client list is the thing to change
_full = (_make(["android_vr", "android"], _FAST), _make(["android_vr", "android"], _SAFE))


def resolve_json(video_id, data_saver=True):
    fast, safe = _saver if data_saver else _full
    url = "https://www.youtube.com/watch?v=" + video_id
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
