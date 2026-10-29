<div align="center">

# ⚡ SparkyTube

**A fast, ad-free, hackable YouTube client for Android.**
Native player, a C++ audio engine, an anime upscaler, and plugins you can write in JS or Kotlin.

[![CI Build](https://github.com/sparkynox/SparkyTube/actions/workflows/android-build.yml/badge.svg)](https://github.com/sparkynox/SparkyTube/actions/workflows/android-build.yml)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84)
![License MIT](https://img.shields.io/badge/license-MIT-blue)

</div>

---

## ✨ Features

### Watching
- 🚫 **No ads.** About 53,000 ad, tracker and anti-adblock domains (EasyList, EasyPrivacy, Anti-Adblock Warning Removal List), plus a C++ filter engine for the path and wildcard rules, plus YouTube-specific rules
- 🎬 **Native ExoPlayer (Media3)** inside a clean Jetpack Compose UI: Home, Watch, Search, Library
- 🎵 **Background audio, audio mode and Picture-in-Picture**
- ⏭️ **Autoplay and queue**: plays your queue first, then the playlist, then the next related video
- 🕘 **Watch history keeps working** even when the app is in the background
- ⬇️ **Downloads** with parallel segments, reserved disk space and resume after a retry

### Speed
- ⚡ **Long videos start fast**: big files are requested in small pieces, so YouTube's throttling doesn't apply
- 🌐 **HTTP/3 (QUIC)** through Cronet when Google Play services has it, OkHttp (HTTP/2) otherwise
- 💾 **256 MB media cache** and **next-video prefetch** sized to your connection speed

### Luno FX (audio, C++)
A realtime audio engine running on the sound itself:
10-band equalizer · bass boost · stereo width · reverb · **8D audio** · volume normalizer · limiter.
Presets: Bass Boost, Lofi Chill, Slowed + Reverb, Nightcore, 8D Audio, Vocal Boost, Loud + Even.

### Anime upscaler (GPU, experimental)
Scales low-resolution anime up (1.5x or 2x, never above 1080p) and runs a GPU pass that keeps line art thin and crisp. Off / Light / Strong. For anime and cartoons only, it turns itself off if your phone can't run it.

### Plugins
- **JS plugins and userscripts** that run inside the YouTube page → [`PLUGINS.md`](PLUGINS.md)
- **Native plugins in Kotlin** (`.dex` / `.jar`) with access to the Activity, the WebView, the player and the audio engine → [`PLUGINS_NATIVE.md`](PLUGINS_NATIVE.md)
- Examples in [`plugins-examples/`](plugins-examples), built by a GitHub Action, no PC needed
- Plugins that talk to ad or tracker domains are refused at install

> ⚠️ Native plugins have the same access as the app. Only install ones you trust.

### Also
- 🖼️ Custom artwork popups (no internet, crash, plugin error, VPN warning…)
- 🧯 Crash reports for Kotlin **and** native (C++) crashes, shown on the next start
- 🛡️ Optional admin switches (disable a feature, ban an install) from a config signed with ed25519 → [`REMOTE_CONFIG.md`](REMOTE_CONFIG.md)

---

## 📥 Download

Grab the latest APK from the [Releases](https://github.com/sparkynox/SparkyTube/releases) page, or from the artifact of a CI run in the [Actions](https://github.com/sparkynox/SparkyTube/actions) tab. Android 7.0 (API 24) or newer.

What's new: [`RELEASE_NOTES_v2.3.md`](RELEASE_NOTES_v2.3.md) · full history: [`CHANGELOG.md`](CHANGELOG.md)

---

## 🛠️ Building

**Requirements:** JDK 17, Android SDK 34, **NDK and CMake 3.22.1** (the app has C/C++ code, install both from the SDK Manager).

```bash
git clone https://github.com/sparkynox/SparkyTube.git
cd SparkyTube
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/`.

**No PC?** Push to GitHub, the [`android-build`](.github/workflows/android-build.yml) workflow builds it for you. Plugins have their own workflow ([`build-plugin.yml`](.github/workflows/build-plugin.yml)).

---

## 🧱 Project layout

| Path | What's in it |
|---|---|
| `app/src/main/java/.../ui` | Compose screens (Home, Watch, Search, popups) |
| `.../player` | Playback service, ExoPlayer setup |
| `.../fx` | Luno FX audio engine glue, anime upscaler, their screens |
| `.../net` | Network stack (OkHttp / Cronet), media cache, downloader, remote config |
| `.../adblock` | Block lists, filter engine, ad rules |
| `.../plugins` | JS and native plugin systems |
| `.../history`, `.../download`, `.../logs` | Background history, downloads, crash and session logs |
| `app/src/main/cpp` | C++: audio engine, filter engine, crash catcher, file io, signature check |
| `app/src/main/assets` | Block lists, injected JS/CSS, plugin runtime |
| `tools/` | Block list updater, remote config signing |
| `plugins-examples/` | Example plugins (JS and Kotlin) |

Stack: Kotlin, Jetpack Compose, Media3, OkHttp, Cronet, C++ (NDK, CMake), yt-dlp through Chaquopy for stream resolving.

---

## 🔄 Updating the block lists

```bash
python3 tools/update_blocklist.py
```

Rebuilds `assets/blocklist_hosts.txt` and `assets/filters_network.txt` from the upstream lists.

---

## 🤝 Contributing

Bug reports, ideas and pull requests are welcome.

1. Fork the repo
2. Create a branch (`git checkout -b feature/cool-thing`)
3. Commit (`git commit -m "Add cool thing"`)
4. Push and open a Pull Request

If you fork it and use the remote config, make your own signing key first, see [`REMOTE_CONFIG.md`](REMOTE_CONFIG.md#forks).

---

## 📄 License and credits

SparkyTube is released under the **MIT License**, see [`LICENSE`](LICENSE).

Some parts keep their own terms (filter lists, the ed25519 code, the artwork): see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

SparkyTube is an independent project and is not affiliated with or endorsed by YouTube or Google. Use it for your own viewing and respect the content creators you watch.

Made by **SparkyNox** · enjoy :3
