# SparkyTube Changelog

## v1.8

### Native Home Feed (Beta, off by default)
- New Settings > Experimental > Native Home Feed toggle — renders the Home tab natively instead of via WebView, using a hand-written InnerTube client (`homefeed/InnerTubeClient.kt`) that reuses whatever YouTube login session is already in the WebView's cookies.
- Scoped to Home only for this release, as a test of the approach before deciding whether to extend it to Search/Subs/other feeds. Falls back to the normal WebView feed automatically if there's no YouTube login or the request fails.
- NewPipeExtractor deliberately doesn't support authenticated feeds by design, so this is a new, from-scratch client rather than an extension of the existing extractor.

### Downloads
- Adaptive-quality downloads (any resolution above what a progressive stream offers) now work again — video-only and audio-only streams are downloaded separately and muxed into one file with FFmpeg, instead of refusing outright.
- This replaces the "Downloads aren't working right now (SABR)" message that only allowed progressive (~360p) downloads.
- Uses `io.github.maxrave-dev:ffmpeg-kit-audio`, a maintained continuation of FFmpegKit — the original arthenica FFmpegKit was officially retired in January 2025 and its binaries pulled from Maven, so it can no longer be added to new projects.

## v1.7

### Profiles
- New "Who's watching?" profile picker (Netflix/Crunchyroll-style) — each profile has its own YouTube login session, its own copy of every Settings toggle, and an optional custom photo picked from your gallery.
- Reachable from Settings > Profiles at any time; shown automatically on app launch once more than one profile exists.
- Long-press a profile card to rename, change photo, or delete it.

### Local Servers (Lumi Fetcher)
- New Settings > Local Servers screen: enable/disable, pick a server backend, set the server address, enter an API key, and check ping/online status.
- New Settings > Extractor Method: Auto (local server → JavaScript → NewPipe), Local Servers only, or NewPipe only.
- Lumi Fetcher (`/sparkytube-server`) rebuilt on yt-dlp via `child_process.spawn` (matches the proven pattern from the earlier music-bot project) after the youtubei.js version turned out unreliable for some videos.
- Fixed a crash where one bad stream format's decipher error could take down the whole local server process.
- Fixed cleartext (`http://`) traffic being blocked by Android's network security defaults, which made the local server unreachable even when running correctly.
- Filtered out HLS/DASH manifest URLs from yt-dlp's format list — these aren't directly playable and were causing "Couldn't play this video" errors.
- Shortened the auto-generated API key from 48 to 8 characters for easier manual entry.

### Performance
- New Settings > Data Saver Mode: blocks YouTube's own telemetry/prefetch beacons, disables automatic image loading, and starts playback at the lowest available quality.
- Fast stream extraction: reads `ytInitialPlayerResponse` directly from the WebView's already-loaded page (no extra network round-trip) before falling back to NewPipeExtractor.

### Player
- Native feed screens (Home/Search/Subs/Shorts/Library) removed — back to the WebView-rendered feed, which fixed slow loading, missing thumbnails, and missing durations that came from the native scraping approach.
- New playback speed control (0.5x–2x, tap to cycle) in the player controls.
- New Picture-in-Picture support — auto-triggers on the Home button while a video is playing full-size; uses the video's real aspect ratio.
- New Music Mode — one tap hides the video surface and keeps audio playing, without affecting the background watch-history WebView.
- New swipe gestures: left half of the player for brightness, right half for volume.
- Fixed pinch-zoom scaling the entire player (including controls) instead of just the video surface.
- Fixed fullscreen mode looking zoomed out/letterboxed — now crops to fill the screen edge-to-edge.
- Fixed the back button leaving the app stuck in landscape after exiting fullscreen.

### Icon
- New app icon.

---
*Next planned release: v1.8, no fixed timeline — whenever the next batch of work is ready.*
