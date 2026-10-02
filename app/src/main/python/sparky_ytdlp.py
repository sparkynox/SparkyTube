import json
import yt_dlp

_common = {
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
    "socket_timeout": 8,
}


def _make(clients):
    opts = dict(_common)
    opts["extractor_args"] = {
        "youtube": {
            "player_client": clients,
            "player_skip": ["configs"],
            "skip": ["translated_subs"],
        }
    }
    return yt_dlp.YoutubeDL(opts)


# both built once at import so resolves after the first reuse them
# data saver: the android client only, small response, ends up at 360p
_ydl_saver = _make(["android"])
# full: android_vr hands out the adaptive 144p..1080p streams without needing
# a js runtime or po token, android stays in for the muxed 360p
# if 1080p doesn't show up, this list is the thing to change
_ydl_full = _make(["android_vr", "android"])


def resolve_json(video_id, data_saver=True):
    ydl = _ydl_saver if data_saver else _ydl_full
    info = ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=False)
    return json.dumps(info, default=str)
