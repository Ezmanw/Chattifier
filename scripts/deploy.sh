#!/usr/bin/env bash
# Build, install on the phone and launch. DEVICE overrides the adb serial.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/sdk/jdk/jdk-21.0.12.1+1}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH=$ANDROID_HOME/platform-tools:$PATH
DEVICE=${DEVICE:-$(adb devices | awk '/razr|ZY22LR56X6/ {print $1; exit}')}
[ -z "$DEVICE" ] && DEVICE=$(adb devices -l | awk '/razr/ {print $1; exit}')
./gradlew -q :app:assembleDebug
adb -s "$DEVICE" install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" shell am start -n com.ethan.localagent/.MainActivity >/dev/null
echo "launched on $DEVICE"
