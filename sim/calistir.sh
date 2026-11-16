#!/usr/bin/env bash
# Deprem simülasyonu serisi (12 saatlik senaryo; %5/%10/%20 yaygınlık x 5 tohum birkaç saat sürer).
#   bash sim/calistir.sh            # -> sim/sonuc-afet.jsonl
#   bash sim/calistir.sh ekip       # -> sim/sonuc-afet-ekip.jsonl (ekiplerde sürekli bağlı telefon, %10)
#   node sim/rapor.js md            # docs/06 tablosu
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ${1:-} == ekip ]]; then
  nice -n 10 node sim/afet.js --yayginlik 0.1 --tohum-sayisi 5 --saat 12 --ekip-ag-gecidi > sim/sonuc-afet-ekip.jsonl
else
  nice -n 10 node sim/afet.js --tohum-sayisi 5 --saat 12 > sim/sonuc-afet.jsonl
fi
