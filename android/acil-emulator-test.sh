#!/usr/bin/env bash
# Emergency relay end to end: the real app on three Android emulators (virtual Bluetooth, netsim) and the real
# authority server on this machine (the emulators reach it at 10.0.2.2).
#   ayse  = emulator-5554: no internet, writes the call
#   kurye = emulator-5556: no internet, carries it
#   gecit = emulator-5558: has internet, uploads it
# "Out of range" is Bluetooth switched off on that phone; "no internet" is Wi-Fi and mobile data switched off.
#   bash android/emulators.sh && bash android/acil-emulator-test.sh
set -euo pipefail
. /d/Android/env.sh
cd "$(dirname "$0")"
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.kairodyne.mesh
PORT=8099
declare -A DEV=([ayse]=emulator-5554 [kurye]=emulator-5556 [gecit]=emulator-5558)
TMP=$(mktemp -d)
TOKEN=pano-$RANDOM$RANDOM

log() { adb -s "${DEV[$1]}" logcat -d -s Kairo:I 2>/dev/null | tr -d '\r'; }
start() { adb -s "${DEV[$1]}" shell am start -n $PKG/.MainActivity "${@:2}" >/dev/null; }
bt() { adb -s "${DEV[$1]}" shell cmd bluetooth_manager "$2" >/dev/null; }
net() { adb -s "${DEV[$1]}" shell svc wifi "$2"; adb -s "${DEV[$1]}" shell svc data "$2"; }
wait_for() {
  local end=$((SECONDS + $3))
  until log "$1" | grep -qE "$2"; do
    if ((SECONDS > end)); then echo "FAIL: $1 never logged /$2/"; log "$1" | tail -15; exit 1; fi
    sleep 2
  done
}
kurum_wait() { # wait until the authority server has logged a line matching $1
  local end=$((SECONDS + $2))
  until grep -qE "$1" "$TMP/sunucu.log"; do
    if ((SECONDS > end)); then echo "FAIL: authority never logged /$1/"; cat "$TMP/sunucu.log"; exit 1; fi
    sleep 2
  done
}

# --- authority server on this machine ------------------------------------------------------------
node ../src/kurum-cli.js anahtar "$TMP/k" "http://10.0.2.2:$PORT" > "$TMP/anahtar.txt"
# debug builds trust the public test root; a release build would refuse this code
KOD=$(node ../src/kurum-cli.js onayla ../test/kok-test "$TMP/k" "Emülatör Test Kurumu" | tail -1)
KURUM_PANO_ANAHTARI=$TOKEN node ../src/kurum-cli.js sunucu "$TMP/k" $PORT > "$TMP/sunucu.log" 2>&1 &
SUNUCU=$!
trap 'kill $SUNUCU 2>/dev/null || true; for p in ayse kurye gecit; do net $p enable >/dev/null 2>&1 || true; bt $p enable || true; done' EXIT
kurum_wait "portunda" 20
echo "authority server up"

# --- phones ------------------------------------------------------------------------------------------
for p in ayse kurye gecit; do
  d=${DEV[$p]}
  adb -s "$d" install -r -g "$APK" >/dev/null
  adb -s "$d" shell pm clear $PKG >/dev/null
  for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb -s "$d" shell pm grant $PKG android.permission.$perm
  done
  bt $p enable
  adb -s "$d" logcat -c
done
net ayse disable; net kurye disable; net gecit enable
sleep 5
for p in ayse kurye gecit; do start "$p"; done
for p in ayse kurye gecit; do wait_for "$p" "READY code=" 30; start "$p" --es kurum "$KOD"; wait_for "$p" "KURUM http" 20; done
wait_for ayse "online=false" 40
wait_for gecit "online=true" 60
echo "PASS setup: authority code on all phones; ayse and kurye offline, gecit online"

# --- 1. a typed call travels ayse -> kurye -> gecit -> authority ----------------------------------
bt gecit disable
for p in ayse kurye; do wait_for "$p" "STATUS peers=1" 90; done
start ayse --es acil "'|||antakya ürgenpaşa mh enkaz altındayız 3 kişi bebek var'"
wait_for ayse "TRIYAJ tur=enkaz aciliyet=1 kisi=3 il=Hatay" 30
echo "PASS triage on the phone: $(log ayse | grep -oE 'TRIYAJ .*' | tail -1)"
wait_for ayse "ACIL_GONDERILDI" 30
wait_for kurye "STATUS peers=[0-9]+ carrying=1" 60
echo "PASS carry: kurye (offline) took ayse's sealed call"
bt ayse disable
bt gecit enable
kurum_wait "ÇAĞRI aciliyet=1 tür=enkaz kişi=3" 180
echo "PASS delivery: the authority received the call through kurye and gecit"
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/v1/acil" > "$TMP/cagrilar.json"
grep -q '"il":"Hatay"' "$TMP/cagrilar.json" && grep -q '"kaynak":"telefon-modeli"' "$TMP/cagrilar.json" \
  || { echo "FAIL: report lacks the model's fields"; cat "$TMP/cagrilar.json"; exit 1; }
echo "PASS record: $(cut -c1-200 "$TMP/cagrilar.json")"
[[ $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/pano") == 401 ]] || { echo "FAIL: dashboard open without token"; exit 1; }
echo "PASS dashboard: closed without the dispatcher token"
if log kurye | grep -q "RECV\|antakya"; then echo "FAIL: kurye logged content it must not be able to read"; exit 1; fi
bt ayse enable
wait_for ayse "ACIL_ULASTI" 180
echo "PASS receipt: ayse (offline) learned that the authority has her call"

# --- 2. an authority announcement reaches the offline phone ---------------------------------------
curl -s -X POST -H "Authorization: Bearer $TOKEN" --data-binary "Toplanma alanı: Atatürk Parkı. Su dağıtımı 14:00." \
  "http://127.0.0.1:$PORT/v1/duyuru" > /dev/null
adb -s "${DEV[gecit]}" shell am force-stop $PKG # a fresh start fetches announcements at once
start gecit
wait_for gecit "DUYURU Toplanma alanı" 90
wait_for ayse "DUYURU Toplanma alanı" 180
echo "PASS announcement: the signed notice reached ayse, who has no internet"

# --- 3. an unconfirmed call survives the app being killed ------------------------------------------
bt kurye disable; bt gecit disable
adb -s "${DEV[ayse]}" logcat -c
start ayse --es acil "'yangin|5||'"
wait_for ayse "ACIL_GONDERILDI" 30
adb -s "${DEV[ayse]}" shell am force-stop $PKG
adb -s "${DEV[ayse]}" logcat -c
start ayse
wait_for ayse "ACIL_YENIDEN" 60
echo "PASS persistence: the unconfirmed call is sent again after the app was killed"
bt kurye enable; bt gecit enable
kurum_wait "ÇAĞRI aciliyet=1 tür=yangin kişi=5" 240
wait_for ayse "ACIL_ULASTI" 240
echo "PASS: the resent call was delivered and confirmed"
TRIYAJ_MS=$(log ayse | grep -oE 'TRIYAJ .*ms=[0-9]+' | head -1 | grep -oE 'ms=[0-9]+')
echo "triage time on the emulator: $TRIYAJ_MS"
echo "ALL PASSED"
