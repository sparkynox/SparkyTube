#!/usr/bin/env python3
# signs the remote config.   python3 tools/rc_sign.py remote_config.src.json
# writes remote_config.json in the repo root. normally the "Sign remote config" GitHub Action
# runs this for you whenever remote_config.src.json changes.
# key: the RC_PRIVATE_KEY environment variable (hex), or tools/rc_private.key
import base64, json, os, sys
import nacl.signing

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, "..")
src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "remote_config.src.json")

hexkey = os.environ.get("RC_PRIVATE_KEY", "").strip()
if not hexkey:
    with open(os.path.join(here, "rc_private.key")) as f:
        hexkey = f.read().strip()
key = nacl.signing.SigningKey(bytes.fromhex(hexkey))

cfg = json.load(open(src))
dest = os.path.join(root, "remote_config.json")

# apps ignore a config whose seq is not higher than one they already saw, so refuse to publish a stuck one
if os.path.exists(dest):
    try:
        old = json.loads(json.load(open(dest))["payload"])
        old_json = json.dumps(old, sort_keys=True)
        if json.dumps(cfg, sort_keys=True) == old_json:
            print("config unchanged, nothing to sign")
            sys.exit(0)
        if cfg.get("seq", 0) <= old.get("seq", 0):
            sys.exit(f"seq must go up: the published config is #{old.get('seq')}, yours is #{cfg.get('seq')}")
    except (KeyError, ValueError):
        pass

payload = json.dumps(cfg, separators=(",", ":"), ensure_ascii=False)
sig = key.sign(payload.encode("utf-8")).signature
with open(dest, "w") as f:
    json.dump({"payload": payload, "sig": base64.b64encode(sig).decode()}, f, indent=1, ensure_ascii=False)
print("signed config #%s ->" % cfg.get("seq"), os.path.abspath(dest))
