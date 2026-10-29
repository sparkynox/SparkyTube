# Remote config (admin switches)

Turn a feature off, or ban an install or an account, without releasing a new app. The app downloads `remote_config.json` from this repo (at most every 6 hours, only when it's opened) and checks its **ed25519 signature in C++**. A config that isn't signed with your key is ignored, so it can't be faked by editing the file on a CDN, through a proxy, or in the app's saved copy (the saved copy is checked again on every start).

Until you set a public key it is completely off.

## Setup

The key pair is already made. The **public** key is in `app/src/main/cpp/rc_pubkey.h` (safe to commit, it's what the app checks signatures against). The **private** key is the hex text in `rc_private.key`, and that one must never go into the repo.

1. Open `rc_private.key`, copy the 64 characters.
2. On GitHub: your repo > Settings > Secrets and variables > Actions > **New repository secret**. Name: `RC_PRIVATE_KEY`, value: the hex. Save.
3. Keep one backup of the hex somewhere private (a password manager), then delete the file from your phone. If you lose the key you can't change the config any more, and if it leaks anyone can. In that case make a new pair with `python3 tools/rc_keys.py`, paste the new public key into `rc_pubkey.h` and release a new build.

## Changing the config (from your phone)

1. Edit `remote_config.src.json` on GitHub and **raise `seq`**.
2. Commit. The **Sign remote config** action signs it into `remote_config.json` and commits that. The app picks it up on its next check.

`seq` must go up every time. An app never accepts a config with a lower `seq` than one it already saw, so an old config can't be replayed to undo a ban (the signing step refuses to publish a stuck `seq`).

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

## Forks

The project is MIT, so anyone can build their own version. A fork that keeps `rc_pubkey.h` as it is trusts configs signed by the original author, and the app downloads from `sparkynox/SparkyTube`. If you fork it, make your own key (`python3 tools/rc_keys.py`), replace the bytes in `rc_pubkey.h` and the `URL` in `RemoteConfig.kt`, or remove the feature.
