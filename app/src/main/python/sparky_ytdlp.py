import json
import yt_dlp

# one YoutubeDL instance for the whole app session, built at import time
# so every resolve after the first reuses it instead of rebuilding
_opts = {
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
    "socket_timeout": 8,
    "extractor_args": {
        "youtube": {
            "player_client": ["android"],
            "player_skip": ["configs"],
            "skip": ["translated_subs"],
        }
    },
}
_ydl = yt_dlp.YoutubeDL(_opts)


def resolve_json(video_id):
    info = _ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=False)
    return json.dumps(info)
