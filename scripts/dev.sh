#!/usr/bin/env bash
# Incremental build → install → relaunch on the running emulator/device.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"

./gradlew :app:installDebug -q --console=plain
"$ADB" shell am start -S -n dev.bettercoool.tv.debug/dev.bettercoool.tv.MainActivity >/dev/null
echo "Launched."
