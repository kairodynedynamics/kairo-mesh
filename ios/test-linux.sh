#!/usr/bin/env bash
# Swift paketini (KairoCore) Linux/WSL üzerinde test eder: bash ios/test-linux.sh
# SWIFT_BIN: Swift 6 araç zincirinin bin klasörü (varsayılan: geliştirme makinesindeki kurulum).
export PATH=${SWIFT_BIN:-/mnt/d/Android/swift/swift-6.0.3-RELEASE-ubuntu22.04/usr/bin}:/usr/bin:/bin
cd "$(dirname "$0")" || exit 1
swift --version 2>&1 | head -1
timeout 1200 nice -n 15 swift test --scratch-path /tmp/kairo-swift-build 2>&1 | tail -20
