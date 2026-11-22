#!/usr/bin/env bash
# Remove Cube Home and restore the stock Amazon home screen.
IP="${1:-192.168.1.62}"; ADB="$HOME/android-dev/platform-tools/adb"; D="$IP:5555"
"$ADB" connect "$D" >/dev/null
"$ADB" -s "$D" shell settings put secure enabled_accessibility_services null
"$ADB" -s "$D" shell settings delete global alarm_manager_constants
"$ADB" -s "$D" shell settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
"$ADB" -s "$D" uninstall dev.cube.launcher
