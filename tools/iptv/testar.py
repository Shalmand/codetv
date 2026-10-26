"""Step 2: tests every stream of the downloaded playlists (the HLS playlist opens and lists video segments that
download) and writes the results. About 1,700 streams, a few minutes.

Usage: python -I tools/iptv/testar.py <folder with the .m3u files> <resultado.json>
"""
import concurrent.futures as cf
import json
import os
import re
import ssl
import sys
import urllib.request
from urllib.parse import urljoin

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from filtros import SKIP_GROUPS, SOURCES  # noqa: E402

src, out = sys.argv[1], sys.argv[2]


def parse(path):
    channels, info, opts = [], None, {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            info = {"name": line.rsplit(",", 1)[-1].strip(), "id": attrs.get("tvg-id", ""),
                    "logo": attrs.get("tvg-logo", ""), "groups": attrs.get("group-title", "")}
            opts = {}
        elif line.startswith("#EXTVLCOPT:"):
            k, _, v = line[11:].partition("=")
            opts[k] = v
        elif line and not line.startswith("#") and info:
            info.update(url=line, ua=opts.get("http-user-agent", ""), referrer=opts.get("http-referrer", ""))
            channels.append(info)
            info = None
    return channels


CTX = ssl.create_default_context()


def get(url, ch, limit=200_000):
    req = urllib.request.Request(url, headers={"User-Agent": ch["ua"] or "Mozilla/5.0 (Linux; Android 9) FreeMyTV"})
    if ch["referrer"]:
        req.add_header("Referer", ch["referrer"])
    with urllib.request.urlopen(req, timeout=8, context=CTX) as r:
        return r.status, r.geturl(), r.read(limit)


def test(ch):
    try:
        status, final, body = get(ch["url"], ch)
        text = body.decode("utf-8", "replace")
        if status != 200 or "#EXTM3U" not in text[:2000]:
            return False
        lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
        if "#EXT-X-STREAM-INF" in text and lines:   # master playlist: open the first variant
            status, final, body = get(urljoin(final, lines[0]), ch)
            text = body.decode("utf-8", "replace")
            lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
        if "#EXTINF" not in text or not lines:
            return False
        status, _, seg = get(urljoin(final, lines[-1]), ch, 4096)
        return status == 200 and len(seg) > 0
    except Exception:  # noqa: BLE001 - any failure means "doesn't play"
        return False


seen, jobs = set(), []
for section, files in SOURCES.items():
    for f in files:
        for ch in parse(os.path.join(src, f.replace("/", "_") + ".m3u")):
            groups = {g.strip().lower() for g in ch["groups"].split(";")}
            if groups & SKIP_GROUPS or "[geo-blocked]" in ch["name"].lower() or ch["url"] in seen:
                continue
            seen.add(ch["url"])
            ch["set"] = section
            jobs.append(ch)

print(f"testing {len(jobs)} streams...", flush=True)
with cf.ThreadPoolExecutor(48) as pool:
    for ch, ok in zip(jobs, pool.map(test, jobs)):
        ch["ok"] = ok
json.dump(jobs, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for section in SOURCES:
    g = [c for c in jobs if c["set"] == section]
    print(f"{section}: {sum(c['ok'] for c in g)} working of {len(g)}")
