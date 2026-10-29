#!/usr/bin/env python3
# rebuilds app/src/main/assets/blocklist_hosts.txt from EasyList, EasyPrivacy and the Anti-Adblock list.
# run it on a PC/CI: python3 tools/update_blocklist.py
import re, urllib.request

RAW = "https://raw.githubusercontent.com/easylist"
FILES = (
    [("easylist", f"easylist/easylist_{n}.txt") for n in ("adservers", "adservers_popup", "thirdparty", "thirdparty_popup", "general_block", "general_block_popup")]
    + [("easylist", f"easyprivacy/easyprivacy_{n}.txt") for n in (
        "trackingservers", "trackingservers_admiral", "trackingservers_general", "trackingservers_international",
        "trackingservers_mining", "trackingservers_notifications", "trackingservers_thirdparty",
        "thirdparty", "thirdparty_international", "general", "general_emailtrackers")]
    + [("antiadblockfilters", f"antiadblockfilters/antiadblock_{l}.txt") for l in (
        "english", "german", "french", "spanish", "russian", "italian", "dutch", "polish", "chinese", "arabic",
        "czech", "finnish", "hebrew", "indonesian", "latvian", "romanian", "slovak")]
)

# big sites the app needs, a list entry for these exact names is dropped
NEVER = {"youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com",
         "gstatic.com", "google.com", "googleapis.com", "gvt1.com", "gvt2.com", "youtube-nocookie.com",
         "github.com", "githubusercontent.com", "cloudflare.com", "jsdelivr.net", "apple.com", "microsoft.com",
         "amazonaws.com", "cloudfront.net", "akamaihd.net", "akamaized.net"}
OK_OPTS = {"third-party", "3p", "script", "image", "popup", "xmlhttprequest", "xhr", "subdocument", "stylesheet",
           "ping", "media", "font", "other", "important", "all", "websocket", "object"}
HOST = re.compile(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\.[a-z][a-z0-9-]+$")


def domains(text):
    out = set()
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("||") or "##" in line or "#@#" in line or "#?#" in line:
            continue
        m = re.match(r"^\|\|([^/^$*|]+)\^?(\|)?(\$(.*))?$", line)
        if not m:
            continue
        h = m.group(1).lower()
        if "^" not in line and "$" not in line and not line.endswith(h):
            continue
        opts = m.group(4)
        if opts and any(p.strip().split("=")[0] not in OK_OPTS for p in opts.split(",")):
            continue
        if HOST.match(h) and h not in NEVER:
            out.add(h)
    return out


hosts = set()
for repo, path in FILES:
    url = f"{RAW}/{repo}/master/{path}"
    try:
        hosts |= domains(urllib.request.urlopen(url, timeout=60).read().decode("utf-8", "ignore"))
    except Exception as e:
        print("skipped", path, e)

with open("app/src/main/assets/blocklist_hosts.txt", "w") as f:
    f.write("\n".join(sorted(hosts)) + "\n")
print(len(hosts), "hosts")

# ---- full network rules (paths, wildcards, options) for the native engine, assets/filters_network.txt ----
EXTRA = [("easylist", f"easylist/easylist_{n}.txt") for n in ("specific_block", "specific_block_popup", "general_block_dimensions")] + \
        [("easylist", f"easyprivacy/easyprivacy_{n}.txt") for n in ("specific", "specific_international", "specific_perimeterx")]
NET_OPTS = {"third-party", "3p", "~third-party", "1p", "first-party", "important", "match-case", "script", "image",
            "stylesheet", "css", "object", "xmlhttprequest", "xhr", "subdocument", "frame", "ping", "media", "font",
            "other", "popup", "websocket"}
COSMETIC = re.compile(r"##|#@#|#\?#|#\$#|#%#|#@\$#|#@\?#")
HOSTONLY = re.compile(r"^\|\|[a-z0-9.-]+\^?(\$.*)?$")


def network_rules(text):
    out = []
    for line in text.splitlines():
        l = line.strip()
        if not l or l[0] in "![" or COSMETIC.search(l):
            continue
        body = l[2:] if l.startswith("@@") else l
        d = body.rfind("$")
        opts, pat = None, body
        if d >= 0 and re.fullmatch(r"[A-Za-z0-9\-_,=|~.]+", body[d + 1:] or "#"):
            opts, pat = body[d + 1:], body[:d]
        if len(pat) > 2 and pat[0] == "/" and pat[-1] == "/":
            continue
        if opts and any(o.split("=")[0] != "domain" and o not in NET_OPTS for o in opts.split(",")):
            continue
        if not l.startswith("@@") and HOSTONLY.match(l):
            continue          # plain domain rules are already in blocklist_hosts.txt
        out.append(l)
    return out


rules, seen = [], set()
for repo, path in FILES + EXTRA:
    url = f"{RAW}/{repo}/master/{path}"
    try:
        for r in network_rules(urllib.request.urlopen(url, timeout=60).read().decode("utf-8", "ignore")):
            if r not in seen:
                seen.add(r)
                rules.append(r)
    except Exception as e:
        print("skipped", path, e)

with open("app/src/main/assets/filters_network.txt", "w") as f:
    f.write("\n".join(rules) + "\n")
print(len(rules), "network rules")
