# Third-party notices

SparkyTube's own code is MIT (see `LICENSE`). Some things inside the repo and the app are not, and keep their own terms.

## Filter lists (not MIT)

`app/src/main/assets/blocklist_hosts.txt` and `app/src/main/assets/filters_network.txt` are generated from:

- **EasyList**, **EasyPrivacy** and the **Adblock Warning Removal List** by the EasyList authors (https://easylist.to, https://github.com/easylist)

These lists are dual licensed under **CC BY-SA 3.0** and **GPL v3 or later**. The generated files are derived from them, so they stay under those terms (attribution, and share-alike if you redistribute changes). They are data files read by the app, they don't change the license of the app's code. `tools/update_blocklist.py` rebuilds them.

## Code

- **ed25519** (signature check for the remote config), by Orson Peters, zlib license: `app/src/main/cpp/ed25519/` (license file included there)
- **AndroidX Media3** (ExoPlayer, OkHttp and Cronet data sources, effects), Apache License 2.0
- **OkHttp**, Apache License 2.0
- **Cronet** comes from Google Play services and is under its terms, it is not bundled in the APK

## Artwork (not MIT)

The expression images in `app/src/main/res/drawable-nodpi/emote_*.webp` are cut from third-party anime artwork and screenshots. The MIT license does **not** cover them and the repo owner doesn't hold their copyright. Before you publish or distribute builds widely, replace them with original or properly licensed art, or get permission.
