# LazyLuno changelog

The version lives in `LazyLuno.VERSION` (LazyLuno.kt), bump it with every change to the C++ or to how the app drives it.
It shows in Settings > About and in the first log line of a resolve.

## v2.0.0 (SparkyTube 2.6)
- Social resolver (Kotlin, `extractor/social/`): Instagram (reels, videos, pictures, carousels, stories, profile pictures), X (videos, GIFs, pictures, profile pictures and banners), TikTok (videos with the quality ladder, photo posts, the sound), Facebook (videos and reels in HD/SD, pictures)
- Short and share links are followed first (vm.tiktok.com, t.co, fb.watch, instagram /share/)
- Site specifics (Instagram query id and app id, Facebook patterns) live in `lazyluno_rules.json` in the repo root and are refreshed in the background every 6 hours, so a broken site can be fixed without an APK
- Downloads go through DownloadManager with the headers the site wants (TikTok cookies and referer), so they show on the Downloads screen
- SparkyTube is in the Android share menu ("SparkyTube Downloader")

## v1.1.0 (SparkyTube 2.6)
- New client ANDROID_VR (Quest app): the whole 144p-4K ladder with no PO token and no login. First in the cascade and in the background quality upgrade, next to IOS
- Age-restricted videos: `AGE` client mode tries the signed in clients first (TVHTML5, TV embedded, WEB_CREATOR, MWEB, WEB) and sends a per-video PO token in the player request (`SpoofConfig::poToken`, 7th argument of nativeResolve). The age gate is detected and reported (`age` in the answer), the app remembers such videos
- Live: the clients with an HLS manifest (IOS, ANDROID_VR, TVHTML5, MWEB) are asked first for videos known to be live, ExoPlayer starts chunkless and capped at the default quality
- Client names understood by the JNI side: ANDROID_APP, IOS, TVHTML5, TV_EMBEDDED, TVHTML5_SIMPLY, MWEB, WEB, WEB_CREATOR, ANDROID_VR, AGE

## v1.0.0 (SparkyTube 2.5)
- First release: ANDROID / IOS / TV clients, signature cipher, HLS, native HTTP through OkHttp
