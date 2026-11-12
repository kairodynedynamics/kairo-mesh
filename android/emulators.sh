#!/usr/bin/env bash
# Boots the three test phones (kairo1-3, created with avdmanager) headless and waits until Bluetooth is on.
#   bash android/emulators.sh        # boot
#   bash android/emulators.sh stop   # shut down
# Refuses to start when drive C has under 6 GB free: Windows grows its page file there under memory pressure.
set -euo pipefail
. /d/Android/env.sh
SERIALS=(emulator-5554 emulator-5556 emulator-5558)

if [[ ${1:-} == stop ]]; then
  for s in "${SERIALS[@]}"; do adb -s "$s" emu kill >/dev/null 2>&1 || true; done
  exit 0
fi

free_c=$(df -BG --output=avail /c | tail -1 | tr -dc 0-9)
if ((free_c < 6)); then echo "only ${free_c} GB free on C:, not starting emulators"; exit 1; fi

for n in 1 2 3; do
  sed -i 's/^hw.ramSize=.*/hw.ramSize=1536/' "/d/Android/avd/kairo$n.avd/config.ini"
  (emulator -avd "kairo$n" -port $((5552 + 2 * n)) -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory 1536 > "/d/Android/tmp/emu$n.log" 2>&1 &)
done
for s in "${SERIALS[@]}"; do
  adb -s "$s" wait-for-device
  until [[ $(adb -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do sleep 3; done
  adb -s "$s" shell cmd bluetooth_manager enable >/dev/null
  echo "$s up"
done
df -h /c | tail -1
