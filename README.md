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
checks GitHub Releases (`/releases/latest`, falling back to `/releases` if
that's empty — e.g. if your release is marked "pre-release") against the
installed `versionName`. If a newer tag is published, you get **both**:
- a system notification
- an in-app dialog: Version, Release Date, Changelog, with **Download**
  (opens the GitHub release page — not a direct APK link) and
  **GitHub Issues** buttons

**IMPORTANT — this is the #1 reason the updater silently does nothing:**
`versionName` in `app/build.gradle.kts` MUST be bumped to match your new
tag *before* you build and tag the release. The update check compares the
*installed app's* `versionName` against the tag on GitHub — if you tag
`v1.1` but the APK you built still says `versionName = "1.0"`... wait, that
comparison would actually still work (1.1 > 1.0). The real trap is the
reverse: if you forget to bump `versionName` at all and it's still `"1.0"`
from a previous release, a new tag will still be detected correctly next
time — but users who already have `1.1` installed won't get renotified for
`1.1` again (correct behavior), and if you *re-push the same tag* without
bumping the version, nothing will appear to happen because there's nothing
newer than what's already installed.

To ship an update: bump `versionCode` AND `versionName` in
`app/build.gradle.kts`, commit, then push a matching tag
(`git tag v1.1 && git push origin v1.1`). The release workflow builds +
attaches the signed APK automatically, and that's what the update check
picks up on everyone's next cold start.

## Background playback reliability

Two things had to be right for the notification + background playback to
work reliably (v1.1 fix):

1. **A real `MediaController` must connect to the session.** A plain
   `bindService()` with a custom binder (the old approach) never triggers
   Media3's internal notification flow — only a proper
   `MediaController.Builder(...).buildAsync()` connection does. See
   `MainActivity.connectMediaController()`.
2. **Battery optimization exemption.** Many OEM skins (MIUI, OxygenOS,
   ColorOS, etc.) aggressively kill background services regardless of the
   foreground notification. SparkyTube requests exemption via the standard
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` system dialog on first
   launch — if a user denies it, background playback may still get killed
   by their OEM's battery manager, which is outside what any app can
   override.

## Why the default YouTube player never loads

`AdBlockEngine` permanently blocks every request to `googlevideo.com` (the
CDN YouTube's own web player streams from) inside the WebView. This means
the WebView's `<video>` element can never actually receive stream data —
not just visually hidden, genuinely starved of the network response. Native
playback goes through `StreamExtractor` (NewPipeExtractor), which uses its
own HTTP client outside the WebView and is unaffected by this block. If
extraction fails for a given video, the user sees an error dialog — there's
intentionally no fallback to WebView playback.

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
