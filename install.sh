#!/usr/bin/env bash
# Install/update Cube Home on the Fire TV Cube and (re)enable its Home-button service.
# Usage: ./install.sh [ip]     (default 192.168.1.62)
set -euo pipefail
IP="${1:-192.168.1.62}"; ADB="$HOME/android-dev/platform-tools/adb"; D="$IP:5555"
cd "$(dirname "$0")"
"$ADB" connect "$D" >/dev/null
[ -f build/screensaver.apk ] || ./build.sh
"$ADB" -s "$D" install -r build/screensaver.apk
"$ADB" -s "$D" install -r build/cubehome.apk
# Home-key signal from the system log (fast path; the app falls back to window events without it).
"$ADB" -s "$D" shell pm grant dev.cube.launcher android.permission.READ_LOGS
# Lets Cube Home turn its Home service back on when you open it (e.g. after a force stop).
"$ADB" -s "$D" shell pm grant dev.cube.launcher android.permission.WRITE_SECURE_SETTINGS
SVC=dev.cube.launcher/dev.cube.launcher.HomeService
# Toggle off/on: the system only rebinds the service after an update this way.
"$ADB" -s "$D" shell settings put secure enabled_accessibility_services null
sleep 1
"$ADB" -s "$D" shell settings put secure enabled_accessibility_services "$SVC"
"$ADB" -s "$D" shell settings put secure accessibility_enabled 1
# Let the ~20 ms launch alarm fire immediately (skips the 5 s post-Home app-switch lock).
"$ADB" -s "$D" shell settings put global alarm_manager_constants min_futurity=0
# Cube Home's screensaver (aerial videos, no ads) instead of Amazon's.
"$ADB" -s "$D" shell settings put secure screensaver_components dev.cube.screensaver/.Screensaver
# Let Cube Home install its own updates (Android still asks for confirmation each time).
"$ADB" -s "$D" shell appops set dev.cube.launcher REQUEST_INSTALL_PACKAGES allow
"$ADB" -s "$D" shell am start -n dev.cube.launcher/.MainActivity >/dev/null
echo "Cube Home installed and Home-button service enabled."
