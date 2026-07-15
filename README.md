# SparkyTube

Custom Android client for m.youtube.com. Premium black UI, native ad/tracker
blocking (WebView request-level, not a Chrome extension — WebView can't run
those), and **real native playback via ExoPlayer + NewPipeExtractor** — video
plays through ExoPlayer, not YouTube's in-page HTML5 player.

## How playback works

1. `injected.js` (always running inside the WebView page) detects when a
   `<video>` element is active and reports it through the `SparkyBridge` JS
   interface every ~800ms.
2. `MainActivity` extracts the video ID from the current URL and calls
   `StreamExtractor.resolvePlayableUrl()`, which uses **NewPipeExtractor** to
   resolve a real, directly-playable stream URL (progressive first, HLS
   fallback).
3. The WebView's own `<video>` element gets paused + muted, and the resolved
   URL is handed to `PlaybackService` (ExoPlayer + MediaSession), which
   renders into the `PlayerView` overlay — that's the actual video surface.
4. If extraction fails (age-gated video, YouTube shipped a player change
   NewPipe hasn't caught up to yet, etc.), it silently stays on WebView
   playback instead of crashing.

## Ad blocking

`AdBlockEngine` intercepts every WebView network request via
`shouldInterceptRequest` and blocks known ad/tracker domains (curated from
uBlock Origin / uBO-Lite's public filter lists — see `assets/blocklist.json`).
This runs 24/7 for the whole session, no toggle. `injected.css`/`injected.js`
add a cosmetic backstop layer for any ad slot that slips through.

## In-app update notifications

Sideloaded APK = no Play Store auto-update. On every cold start, the app
checks `https://api.github.com/repos/sparkynox/SparkyTube/releases/latest`
against the installed `versionName`. If a newer tag is published, you get
**both**:
- a system notification (visible even if you've backgrounded the app)
- an in-app dialog with a "Download" button straight to the new APK

To actually trigger this for your users: bump `versionName` in
`app/build.gradle.kts`, then push a tag (`git tag v1.1.0 && git push origin v1.1.0`).
The release workflow builds + attaches the signed APK automatically, and
that's what the update check picks up.

## Building locally (Termux)

```bash
git clone https://github.com/sparkynox/SparkyTube.git
cd SparkyTube
chmod +x gradlew
./gradlew assembleDebug
# APKs land in app/build/outputs/apk/debug/
```

## Pushing from Termux

```bash
git add . && git commit -m "msg" && git push -f https://$(gh auth token)@github.com/sparkynox/SparkyTube.git main
```

## GitHub Actions CI

Every push to `main` triggers `.github/workflows/android-build.yml`, which
builds debug APKs (armeabi-v7a, arm64-v8a, and a universal APK) and uploads
them as a downloadable workflow artifact — no local build needed.

### Release builds (signed)

Pushing a tag like `v1.0.0` also builds a **signed release APK** and attaches
it to a GitHub Release. This needs three repo secrets set once
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 your-release.keystore` output |
| `KEYSTORE_PASSWORD` | your keystore password |
| `KEY_ALIAS` | your key alias |
| `KEY_PASSWORD` | your key password |

If you haven't made a keystore yet:

```bash
keytool -genkey -v -keystore release.keystore -alias sparkytube \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore
```

Without these secrets set, release builds still succeed — they just fall
back to the auto-generated debug key, so tag/push flow never breaks; just
re-sign properly before publishing anywhere that matters (Play Store etc.
won't accept a debug-signed APK anyway).

## Known limitation

Stream extraction depends on NewPipeExtractor staying in sync with YouTube's
player. If a video suddenly stops native-playing, bump the NewPipeExtractor
version in `app/build.gradle.kts` first — that's almost always the fix.
