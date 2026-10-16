#!/usr/bin/env bash
# Tiny UI driver for testing on the phone: `ui.sh tap "Text"`, `ui.sh dump`, `ui.sh shot out.png`.
set -euo pipefail
export PATH=$HOME/Android/Sdk/platform-tools:$PATH
DEVICE=${DEVICE:-$(adb devices -l | awk '/razr/ {print $1; exit}')}
A="adb -s $DEVICE"
case "$1" in
  dump) $A shell uiautomator dump --display 0 /sdcard/ui.xml >/dev/null 2>&1 || $A shell uiautomator dump /sdcard/ui.xml >/dev/null; $A shell cat /sdcard/ui.xml | grep -o '\(text\|content-desc\)="[^"]\+"[^>]*bounds="[^"]*"' | sed 's/ resource-id="[^"]*"//; s/ class="[^"]*"//; s/ package="[^"]*"//' ;;
  tap)
    $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    B=$($A shell cat /sdcard/ui.xml | grep -o "\(text\|content-desc\)=\"$2\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
    [ -z "$B" ] && { echo "not found: $2"; exit 1; }
    set -- $B; $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); echo "tapped" ;;
  shot) ID=$($A shell dumpsys SurfaceFlinger --display-id | head -1 | grep -o 'Display [0-9]*' | awk '{print $2}'); $A exec-out screencap -p -d "$ID" > "$2" ;;
esac
