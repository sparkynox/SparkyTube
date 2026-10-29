#!/usr/bin/env python3
# signs a remote config.   python3 tools/rc_sign.py tools/remote_config.example.json
# writes remote_config.json in the repo root, commit and push that file
import base64, json, os, sys
import nacl.signing

src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "remote_config.example.json")
keyfile = os.path.join(os.path.dirname(__file__), "rc_private.key")
key = nacl.signing.SigningKey(bytes.fromhex(open(keyfile).read().strip()))

payload = json.dumps(json.load(open(src)), separators=(",", ":"), ensure_ascii=False)
sig = key.sign(payload.encode("utf-8")).signature
out = {"payload": payload, "sig": base64.b64encode(sig).decode()}
dest = os.path.join(os.path.dirname(__file__), "..", "remote_config.json")
with open(dest, "w") as f:
    json.dump(out, f, indent=1, ensure_ascii=False)
print("wrote", os.path.abspath(dest))
