# Third-party notices

SparkyTube's own code is GPL-3.0 (see `LICENSE`). Some things inside the repo and the app are not, and keep their own terms.

## Filter lists (own terms)

`app/src/main/assets/blocklist_hosts.txt` and `app/src/main/assets/filters_network.txt` are generated from:

- **EasyList**, **EasyPrivacy** and the **Adblock Warning Removal List** by the EasyList authors (https://easylist.to, https://github.com/easylist)

These lists are dual licensed under **CC BY-SA 3.0** and **GPL v3 or later**. The generated files are derived from them, so they stay under those terms (attribution, and share-alike if you redistribute changes). They are data files read by the app, they don't change the license of the app's code. `tools/update_blocklist.py` rebuilds them.

## Code

- **ed25519** (signature check for the remote config), by Orson Peters, zlib license: `app/src/main/cpp/ed25519/` (license file included there)
- **AndroidX Media3** (ExoPlayer, OkHttp and Cronet data sources, effects), Apache License 2.0
- **OkHttp**, Apache License 2.0
- **Cronet** comes from Google Play services and is under its terms, it is not bundled in the APK

## Artwork (own terms)

The expression images in `app/src/main/res/drawable-nodpi/emote_*.webp` are cut from third-party anime artwork and screenshots. The GPL does **not** cover them and the repo owner doesn't hold their copyright. Before you publish or distribute builds widely, replace them with original or properly licensed art, or get permission.


## Ambient light for YouTube (logic reference)

The live ambient mode (layered, scaled and blurred copies of the current video frame, black bar
detection, frame blending, saturation/brightness adjustment) follows the approach of the
"Ambient light for YouTube" browser extension. SparkyTube's code is a new implementation for
Android (Kotlin, Compose and C++), no code was copied.

MIT License

Copyright (c) 2017 Wessel Kroos

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
