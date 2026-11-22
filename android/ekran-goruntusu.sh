#!/usr/bin/env bash
# Boot one emulator (kairo1), install the demo APK, screenshot the emergency screen step by step, shut down.
# Build first: ./gradlew assembleDemo. Screenshots go to android/build/ekran/.
set -u
export MSYS_NO_PATHCONV=1
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-D:/Android/sdk} ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-D:/Android/avd}; export ANDROID_HOME=$ANDROID_SDK_ROOT
SDK=$(cygpath -u "$ANDROID_SDK_ROOT")
ADB="$SDK/platform-tools/adb.exe"
OUT="$(dirname "$0")/build/ekran"
APK=$(dirname "$0")/app/build/outputs/apk/demo/app-demo.apk
mkdir -p "$OUT"
"$SDK/emulator/emulator.exe" -avd kairo1 -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -memory 1536 -gpu swiftshader_indirect >"$OUT/emu.log" 2>&1 &
S=emulator-5554
timeout 180 "$ADB" -s $S wait-for-device || { tail -5 "$OUT/emu.log"; exit 1; }
for i in $(seq 1 120); do [ "$("$ADB" -s $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
echo "boot: $i"
# no network: a call pressed during the test must stay on the emulator
"$ADB" -s $S shell svc wifi disable; "$ADB" -s $S shell svc data disable
"$ADB" -s $S uninstall com.kairodyne.mesh; "$ADB" -s $S install -g "$(cygpath -w "$APK")" | tail -1
P=com.kairodyne.mesh
"$ADB" -s $S shell am start -n $P/.MainActivity >/dev/null; sleep 5
shot() { "$ADB" -s $S exec-out screencap -p > "$OUT/$1.png"; }
shot 1-ust
# tap the first emergency tile ("Enkaz altındayım") using its on-screen bounds
"$ADB" -s $S shell uiautomator dump /sdcard/d.xml >/dev/null; "$ADB" -s $S pull /sdcard/d.xml "$OUT/d.xml" >/dev/null
xy() { python -c "import re,sys;s=open(sys.argv[1],encoding='utf-8').read();m=re.search(r'(?:text|content-desc)=\"'+re.escape(sys.argv[2])+r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',s);print((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else print('')" "$OUT/d.xml" "$1"; }
read X Y <<<"$(xy 'Enkaz altındayım')"; [ -n "$X" ] && "$ADB" -s $S shell input tap $X $Y; sleep 1
shot 2-secili
"$ADB" -s $S shell input swipe 540 1700 540 700 400; sleep 1
"$ADB" -s $S shell uiautomator dump /sdcard/d.xml >/dev/null; "$ADB" -s $S pull /sdcard/d.xml "$OUT/d.xml" >/dev/null
read X Y <<<"$(xy 'Bebek')"; [ -n "$X" ] && "$ADB" -s $S shell input tap $X $Y
read X Y <<<"$(xy 'Bir artır')"; [ -n "$X" ] && { "$ADB" -s $S shell input tap $X $Y; "$ADB" -s $S shell input tap $X $Y; }
sleep 1; shot 3-orta
"$ADB" -s $S shell input swipe 540 1700 540 500 400; sleep 1
shot 4-alt
"$ADB" -s $S shell uiautomator dump /sdcard/d.xml >/dev/null; "$ADB" -s $S pull /sdcard/d.xml "$OUT/d.xml" >/dev/null
read X Y <<<"$(xy 'Çağrıyı gönder')"
if [ -n "$X" ]; then
  "$ADB" -s $S shell input tap $X $Y; sleep 1; shot 5-geri-sayim
  "$ADB" -s $S shell input tap 540 1519; sleep 1; shot 6-iptal
fi
"$ADB" -s $S logcat -d | grep -E "AndroidRuntime|FATAL" | head -5
"$ADB" -s $S uninstall com.kairodyne.mesh; "$ADB" -s $S emu kill >/dev/null 2>&1
ls "$OUT"
