# builds the binary host index the app mmaps (format described in app/src/main/cpp/hostindex.cpp)
import hashlib, struct

def fnv(s):
    h = 0xcbf29ce484222325
    for b in s.lower().encode():
        h = ((h ^ b) * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h

def build(hosts, path):
    hs = sorted({fnv(h) for h in hosts})
    with open(path, "wb") as f:
        f.write(b"STHB" + struct.pack("<III", 1, len(hs), 0))
        f.write(struct.pack("<%dQ" % len(hs), *hs))
    return len(hs)

def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()

if __name__ == "__main__":
    import sys
    hosts = [l.strip() for l in open(sys.argv[1]) if l.strip()]
    print(build(hosts, sys.argv[2]), "hashes")
