#!/usr/bin/env bash
# End-to-end test on three Android emulators sharing a virtual Bluetooth radio (netsim).
#   ali   = emulator-5554, kurye = emulator-5556, bora = emulator-5558, all already booted.
#   bash android/emulator-test.sh
# 1. direct:  ali and bora both running, ali -> bora
# 2. courier: bora is off while ali hands a message to kurye; ali is off when bora comes back; kurye delivers.
set -euo pipefail
. /d/Android/env.sh
cd "$(dirname "$0")"
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.kairodyne.mesh
declare -A DEV=([ali]=emulator-5554 [kurye]=emulator-5556 [bora]=emulator-5558)
declare -A CODE

log() { adb -s "${DEV[$1]}" logcat -d -s Kairo:I 2>/dev/null | tr -d '\r'; }
start() { adb -s "${DEV[$1]}" shell am start -n $PKG/.MainActivity "${@:2}" >/dev/null; }
stop() { adb -s "${DEV[$1]}" shell am force-stop $PKG; }
# wait_for <phone> <regex> <seconds>
wait_for() {
  local end=$((SECONDS + $3))
  until log "$1" | grep -qE "$2"; do
    if ((SECONDS > end)); then echo "FAIL: $1 never logged /$2/"; log "$1" | tail -15; exit 1; fi
    sleep 2
  done
}

for p in ali kurye bora; do
  d=${DEV[$p]}
  adb -s "$d" install -r -g "$APK" >/dev/null
  adb -s "$d" shell pm clear $PKG >/dev/null
  for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE; do adb -s "$d" shell pm grant $PKG android.permission.$perm; done
  adb -s "$d" logcat -c
done
sleep 5 # pm clear removes the old task asynchronously and would kill an app started right after it
for p in ali kurye bora; do start "$p"; done
for p in ali kurye bora; do
  wait_for "$p" "READY code=" 30
  CODE[$p]=$(log "$p" | grep -oE "READY code=[A-Za-z0-9+/=]+" | tail -1 | cut -d= -f2-)
  echo "$p code ${CODE[$p]}"
done
for p in ali kurye bora; do
  for q in ali kurye bora; do [[ $p == "$q" ]] || start "$p" --es add "$q=${CODE[$q]}"; done
done
for p in ali kurye bora; do wait_for "$p" "STATUS peers=2" 90; done
echo "all three phones connected over Bluetooth"
prefs=$(adb -s "${DEV[ali]}" shell run-as $PKG cat shared_prefs/kairo.xml | tr -d '\r')
if ! grep -q 'name="secret_enc"' <<<"$prefs" || grep -q 'name="secret"' <<<"$prefs"; then echo "FAIL: private key not wrapped by the Keystore"; exit 1; fi
echo "PASS keystore: the stored private key is encrypted"

echo "--- 1. direct message"
start ali --es send "'bora=merhaba bora, doğrudan'"
wait_for bora "RECV from=ali text=merhaba bora, doğrudan" 60
echo "PASS direct: bora received ali's message"

echo "--- 2. courier"
stop bora
sleep 3
# kurye may still hold a copy of the first message (bora left before its receipt reached kurye), so wait for one more
before=$(log kurye | grep -oE "carrying=[0-9]+" | tail -1 | cut -d= -f2)
for p in ali kurye; do adb -s "${DEV[$p]}" logcat -c; done
start ali --es send "'bora=okul bahçesinde buluşalım'"
wait_for kurye "STATUS peers=[0-9]+ carrying=$((before + 1))" 60
echo "kurye is carrying the sealed message (carrying went $before -> $((before + 1)))"
# kurye's screen goes home and Android reclaims background apps: the foreground service must keep the mesh alive
adb -s "${DEV[kurye]}" shell input keyevent KEYCODE_HOME
adb -s "${DEV[kurye]}" shell am kill $PKG
adb -s "${DEV[kurye]}" logcat -c
wait_for kurye "STATUS peers=[0-9]+ carrying=$((before + 1))" 20
echo "PASS background: kurye keeps carrying with the app in the background"
stop ali
adb -s "${DEV[bora]}" logcat -c
start bora
wait_for bora "RECV from=ali text=okul bahçesinde buluşalım" 120
echo "PASS courier: bora received ali's message from kurye while ali was off"
wait_for kurye "STATUS peers=[0-9]+ carrying=0" 60
echo "PASS receipt: kurye dropped its copy after delivery"
if log kurye | grep -q "RECV"; then echo "FAIL: kurye logged a message it should not be able to read"; exit 1; fi
echo "ALL PASSED"
