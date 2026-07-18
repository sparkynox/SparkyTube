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

## Autoplay pre-caching

The 1-3s stall Sparky noticed between one video ending and the next one
starting was the extraction round-trip (NewPipeExtractor hitting the
network) happening *after* the current video already finished. Fixed by
pre-caching: once native ExoPlayer is within ~20s of the end of the current
video, `MainActivity.maybePrefetchNextVideo()` asks `injected.js` which
video autoplay would jump to (`__sparkyPredictNextVideoId()` — same
selector logic as the actual click-trigger, but read-only, no navigation),
then calls `StreamExtractor.prefetch()` in the background. By the time the
video actually ends, the next stream URL is usually already sitting in
StreamExtractor's cache, so playback resumes instantly instead of waiting
on a fresh extraction.

If the predicted next video changes before playback catches up (e.g. the
related-videos list reshuffles), the old pre-cached entry just isn't used —
it expires on its own via the existing 5-minute cache TTL, no explicit
cleanup needed.

## Support the Developer (optional rewarded ads)

A fully opt-in "Support the Developer" option lives in the top-bar overflow
menu (⋮). It's never shown automatically, never on a timer, and never tied
to playback or any other feature — the user has to explicitly tap it, then
confirm they want to watch an ad.

Uses **InMobi** rather than AdMob because InMobi pays out via direct bank
transfer to Indian accounts (no PayPal account needed), with a $50
threshold for India. Live account/placement IDs are already wired in
`support/SupportAdManager.kt`.

**Security note:** since this repo is public, the InMobi Account ID and
Placement ID in `SupportAdManager.kt` are visible to anyone. That's
normal for ad SDK IDs (they're not secret keys — InMobi's server-side
fraud detection is what actually protects revenue, not keeping the ID
hidden), but worth knowing if you ever want to rotate them.

**Build note:** the InMobi SDK resolves via plain `mavenCentral()` — no
custom repository needed. An earlier version of this project pointed at a
custom InMobi Maven URL that turned out to be unreachable
(`repository.mup.inmobi.com` doesn't resolve); that's been removed.

## v1.1 hotfixes (background autoplay + player UI)

**Background/lock-screen autoplay was looping the same video.** Root cause:
Android's WebView throttles JS timer execution when the Activity
backgrounds (same as a browser tab losing focus) — so `__sparkyPlayNext()`
and `__sparkyPredictNextVideoId()` in `injected.js` would run against a
frozen/stale page state, return nothing useful, and the current video just
sat there. Fixed by keeping `webView.resumeTimers()` active whenever native
playback is running (see `onPause()`/`onResume()` in MainActivity), plus
`advanceToNextVideo()` now checks whether the click/navigation actually
succeeded and retries with backoff (up to 4 times) instead of silently
giving up on the first failure.

**Player UI was Media3's stock control layout**, which doesn't reliably
theme against a fully custom dark UI. Replaced with a custom controller
layout (`res/layout/exo_player_controls.xml`) — same rough control
placement as most video apps (play/pause centered, seek bar + time along
the bottom, settings gear top-right) but styled and built specifically for
SparkyTube, not a copy of any other app's assets or code.

**NewPipeExtractor retries** went from a single fixed 700ms retry to 5
attempts with exponential backoff, to survive longer transient network
drops on mobile data.

## v1.1.1 hotfix — leftover YouTube player background

The black/blank gap under the ExoPlayer overlay (with YouTube's own poster
frame peeking through) was caused by only hiding the `<video>` tag itself
via CSS — YouTube's player *container* (`.html5-video-player`,
`ytd-player`, etc.) renders its own background/poster frame independently
of the video element, so that container was still visible and still
occupying layout space underneath. Fixed by collapsing the entire player
container (not just the video tag) to zero height/opacity once native
playback is active — see the `body.sparkytube-native-active` rules in
`injected.css` (and the matching fallback block in `injected.js`).

Also fixed the missing video title: `PlayerView`'s custom controller layout
inflates lazily (only once controls are shown for the first time), so
`findViewById` calls made immediately after `controller.play()` could
return null and silently drop the title. Both the title-setting and
button-wiring code now retry briefly instead of failing silently, and the
controller is set to never auto-hide (`show_timeout="0"`) so the title bar
stays visible rather than requiring a tap to reveal.

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
