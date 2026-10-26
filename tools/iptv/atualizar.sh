#!/usr/bin/env bash
# Refreshes CodeTV's channel list from iptv-org: download, test every stream, keep the working free ones.
# Needs curl and Python 3. Run from the project folder: ./tools/iptv/atualizar.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
work="out/iptv"
mkdir -p "$work"
for p in countries/br countries/pt countries/ar countries/mx countries/co countries/cl countries/pe categories/sports; do
  curl -sSfL -o "$work/$(echo "$p" | tr / _).m3u" "https://iptv-org.github.io/iptv/$p.m3u"
done
curl -sSfL -o "$work/logos.json" "https://iptv-org.github.io/api/logos.json"
python -I tools/iptv/testar.py "$work" "$work/resultado.json"
python -I tools/iptv/montar_lista.py "$work/resultado.json" "$work/logos.json" app/assets/channels/channels.m3u
echo "Done: app/assets/channels/channels.m3u. Build again (./build.sh) and install."
