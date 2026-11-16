#!/usr/bin/env bash
# Triyaj modelinin bütün adımları tek yerden (WSL/Linux, python3 + torch).
#   bash triyaj/calistir.sh veri         # sentetik veri: egitim/dogrulama/test/test_agir_gurultu (+ ayar)
#   bash triyaj/calistir.sh egit [cuda]  # varsayılan CPU, tek dönem; GPU başka işle paylaşılıyorsa CPU daha hızlı
#   bash triyaj/calistir.sh olc          # kural taban çizgisi + model ölçümü -> model/kural-*.json, model/olcum.json
#   bash triyaj/calistir.sh telefon      # telefon/ ağırlıkları (Kotlin/Swift testleri bunlarla karşılaştırır)
#   bash triyaj/calistir.sh hepsi        # sırayla hepsi
# Uzun adımlar oturumdan bağımsız sürsün diye: setsid nohup bash triyaj/calistir.sh hepsi > triyaj/model/calistir.log 2>&1 &
set -euo pipefail
cd "$(dirname "$0")"
export PYTHONIOENCODING=utf-8
adim() {
  case "$1" in
    veri) python3 uret.py && python3 uret.py --ayar && rm -f veri/*.npz ;;
    egit) TRIYAJ_CIHAZ="${2:-cpu}" TRIYAJ_IS="${TRIYAJ_IS:-8}" TRIYAJ_DONEM="${TRIYAJ_DONEM:-1}" python3 model.py egit ;;
    olc)
      for f in test test_agir_gurultu; do python3 degerlendir.py "veri/$f.jsonl" --kural > "model/kural-$f.json"; done
      TRIYAJ_IS="${TRIYAJ_IS:-6}" python3 model.py esik
      TRIYAJ_IS="${TRIYAJ_IS:-6}" python3 model.py rapor ;;
    telefon) python3 model.py agirlik ;;
    hepsi) adim veri; adim egit "${2:-cpu}"; adim telefon; adim olc ;;
    *) sed -n '2,9p' "$0"; exit 1 ;;
  esac
}
adim "$@"
