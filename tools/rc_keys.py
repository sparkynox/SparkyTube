#!/usr/bin/env python3
# makes the ed25519 key pair for the remote config.
#   pip install pynacl
#   python3 tools/rc_keys.py
# the private key is saved to tools/rc_private.key (git-ignored, keep it safe, back it up).
# paste the printed bytes into app/src/main/cpp/rc_pubkey.h
import os, sys
import nacl.signing

path = os.path.join(os.path.dirname(__file__), "rc_private.key")
if os.path.exists(path):
    sys.exit("tools/rc_private.key already exists, delete it first if you really want a new key (the old config will stop working)")

key = nacl.signing.SigningKey.generate()
with open(path, "w") as f:
    f.write(bytes(key).hex())
os.chmod(path, 0o600)

pub = bytes(key.verify_key)
rows = []
for i in range(0, 32, 16):
    rows.append("    " + ", ".join("0x%02x" % b for b in pub[i:i + 16]))
print("#define RC_PUBKEY { \\\n" + ", \\\n".join(rows) + " }")
print("\nprivate key saved in", path)
