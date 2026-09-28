# SparkyTube Changelog (v1.7 → v1.9)

## New Features

- **Chapters**: chapter markers on the seekbar built from the video description timestamps, the current chapter name is shown on the player, and you can tap it to see the full list and jump to any chapter
- **Resume watch position**: videos pick up right where you left off
- **Long-press download**: long-press any video card in the native feed, choose Video or Audio only, pick a quality, and download without opening the video
- **Feed Style**: three options in Settings: Native (RecyclerView), YouTube Skin (the real page with a new look and smooth animations), and Custom HTML (our own feed page)
- **Native Home Feed**: infinite scroll, pull-to-refresh, and redesigned video cards (16:9 thumbnails, channel avatars, Mix/Playlist badges)
- **SponsorBlock**: automatically skips sponsor, self-promo, intro, and outro segments
- **Offline Library**: watch your downloaded videos inside the app
- **Download Manager**: progress, speed, and size for every download, including adaptive downloads
- **Default video quality**: pick it in Settings (144p up to 1080p)
- **Music mode**: now a global toggle, shows the thumbnail, and actually turns off the video track
- **Volume/Brightness gestures**: new on-screen overlay indicator
- **Logs and crash reporter**: a "Check Logs" screen after a crash, plus a log viewer with sharing in Settings
- **Material You**: Material 3 theme, rounded corners, redesigned player controls, and an opt-in dynamic color toggle

## Improvements

- **NewPipe extraction is much faster**: now uses OkHttp with connection pooling and HTTP/2
- **yt-dlp**: now returns the full quality list and adaptive streams, and extracts faster
- **APK size**: removed unused resources and extra language files
- **Downloads**: fixed adaptive muxing, cancel now works properly, added thumbnails, and retries on connection resets
- **Notification**: shows the thumbnail, and tapping it opens the app
- **Background HTML5 player**: plays for only 3 seconds so it no longer doubles bandwidth
- **Extraction fallback chain**: NewPipe, Piped, server, then yt-dlp (age-restricted videos now play)

## Bug Fixes

- Fixed zoom not resetting when exiting fullscreen
- Fixed Mix/Playlist cards doing nothing when tapped
- Fixed the music mode thumbnail blocking touches
- Fixed the background WebView video showing through the thumbnail
- Fixed the black screen on restart with yt-dlp and the R8 stripping crash
- Fixed the app going back to Home after being closed and reopened
- Fixed CI build errors caused by the Gradle wrapper and gradlew

## Known Issues

- Chapters only work for videos resolved through NewPipe, not the yt-dlp or server paths
- Audio-only download has no bitrate choice, you get a single audio option
- The Custom HTML feed puts every video in one "Recommended" shelf, section-wise grouping is still to come
- Long-press download currently works only in the Native (RecyclerView) feed
