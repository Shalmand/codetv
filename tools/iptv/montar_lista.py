"""Step 3: builds app/assets/channels/channels.m3u from the test results: only working free channels (filtros.py),
one section per region, alphabetical, with the category in Portuguese and a logo URL the TV can draw (PNG/JPEG; for
SVG-only logos, Wikimedia's PNG version). The app downloads the logos itself.

Usage: python -I tools/iptv/montar_lista.py <resultado.json> <logos.json> app/assets/channels/channels.m3u
"""
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from filtros import CATEGORY, JUNK, ORDER, PAY, PLUTO, REGION, SPORTS_COUNTRIES  # noqa: E402

tested, api, out = sys.argv[1], sys.argv[2], sys.argv[3]
RANK = {"PNG": 0, "WebP": 1, "JPEG": 2, "GIF": 3}
alternatives = {}
for logo in json.load(open(api, encoding="utf-8")):
    alternatives.setdefault(logo["channel"], []).append(logo)


def is_svg(url):
    return url.lower().split("?")[0].endswith(".svg")


def wikimedia_png(url):
    m = re.match(r"(https://upload\.wikimedia\.org/wikipedia/[^/]+)/(\w/\w\w)/([^/?]+\.svg)$", url.split("?")[0], re.I)
    return f"{m.group(1)}/thumb/{m.group(2)}/{m.group(3)}/500px-{m.group(3)}.png" if m else ""


def logo_for(c):
    if c["logo"] and not is_svg(c["logo"]):
        return c["logo"]
    options = sorted(alternatives.get(c["id"].split("@")[0], []),
                     key=lambda o: (RANK.get(o.get("format") or "", 9), -(o.get("width") or 0)))
    for o in options:
        if not is_svg(o["url"]):
            return o["url"]
    return wikimedia_png(c["logo"]) if c["logo"] else ""


def category(groups):
    names = [CATEGORY.get(g.strip().lower()) for g in groups.split(";")]
    return " · ".join([n for n in names if n][:2])


def fold(s):
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()


keep = []
for c in json.load(open(tested, encoding="utf-8")):
    if not c["ok"] or PLUTO.search(c["url"]) or PLUTO.search(c["name"]):
        continue
    junk = JUNK.search(c["name"]) and not (c["set"] == "Portugal" and "Not 24/7" in c["name"])
    if junk or PAY.search(c["name"].replace(" (720p)", "").replace(" (1080p)", "")):
        continue
    if c["set"] == "Esportes":
        if not SPORTS_COUNTRIES.search(c["id"]):
            continue
        region = "Esportes"
    else:
        m = re.search(r"\.([a-z]{2})@", c["id"])
        region = (REGION.get(m.group(1)) if m else None) or {"Brasil": "Brasil", "Portugal": "Portugal"}.get(c["set"])
        if region is None:
            continue
    c["region"] = region
    c["clean"] = re.sub(r"\s*\((\d+[pi]|[A-Z][\w .]+)\)", "", c["name"])
    c["clean"] = c["clean"].replace("[Geo-blocked]", "").replace("[Not 24/7]", "").strip()
    keep.append(c)

seen, final = set(), []
for c in sorted(keep, key=lambda c: (ORDER.index(c["region"]), fold(c["clean"]))):
    key = (c["region"], fold(c["clean"]))
    if key not in seen:
        seen.add(key)
        final.append(c)

with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("#EXTM3U\n")
    for c in final:
        f.write(f'#EXTINF:-1 tvg-id="{c["id"]}" tvg-logo="{logo_for(c)}" tvg-category="{category(c["groups"])}" '
                f'group-title="{c["region"]}",{c["clean"]}\n')
        if c["ua"]:
            f.write(f'#EXTVLCOPT:http-user-agent={c["ua"]}\n')
        if c["referrer"]:
            f.write(f'#EXTVLCOPT:http-referrer={c["referrer"]}\n')
        f.write(c["url"] + "\n")

for region in ORDER:
    print(f"{region}: {sum(1 for c in final if c['region'] == region)}")
