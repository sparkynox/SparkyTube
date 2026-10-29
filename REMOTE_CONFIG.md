# Remote config (admin switches)

Turn a feature off, or ban an install or an account, without releasing a new app. The app downloads `remote_config.json` from this repo (at most every 6 hours, only when it's opened) and checks its **ed25519 signature in C++**. A config that isn't signed with your key is ignored, so it can't be faked by editing the file on a CDN, through a proxy, or in the app's saved copy (the saved copy is checked again on every start).

Until you set a public key it is completely off.

## Setup (once)

```
pip install pynacl
python3 tools/rc_keys.py
```

- It saves the **private key** to `tools/rc_private.key` (git-ignored). Back it up somewhere safe. If you lose it you can't change the config any more, and if it leaks anyone can.
- Paste the printed `#define RC_PUBKEY {...}` into `app/src/main/cpp/rc_pubkey.h` and build a new app version. Only apps built with that key trust the config.

## Publishing a config

1. Copy `tools/remote_config.example.json`, edit it.
2. `python3 tools/rc_sign.py my_config.json` writes `remote_config.json` in the repo root.
3. Commit and push that file. Apps pick it up on their next check.

Every time you change it, **raise `seq`**. An app never accepts a config with a lower `seq` than one it already saw (so an old config can't be replayed to undo a ban).

## Fields

```json
{
  "seq": 3,
  "exp": 0,
  "features": {
    "audio_fx": { "enabled": false, "message": "Audio FX is down for maintenance." }
  },
  "banned": {
    "message": "You have been banned from SparkyTube.",
    "install_ids": ["1b9d6bcd-..."],
    "handles": ["@someone"]
  }
}
```

- `seq`: version number, always go up.
- `exp`: optional unix time in seconds, after that the config is ignored. `0` = never.
- `features`: switch a feature off with `"enabled": false`. The message is shown in a popup with the "disabled by admin" artwork. Features that exist: `audio_fx`, `video_fx`, `plugins`, `native_plugins`. In code, any screen can check one with `RemoteConfig.gate(activity, "name")`.
- `banned`: matches the install ID (shown in the ban popup and in Settings > Logs at the top of each session) or the signed-in YouTube handle. A banned user gets a popup and the app closes. No match, no effect.

## What it can and can't do

It protects the *config*: nobody can switch features or bans on or off by tampering with the file or the phone's storage. It does not protect the app from someone who rebuilds the APK without the check, nothing client-side can.
