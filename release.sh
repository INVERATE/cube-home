#!/usr/bin/env bash
# Publish VERSION as a GitHub release (tag v<VERSION>, asset cubehome.apk), then bump VERSION.
# The TV's self-updater installs any release whose number is higher than the installed one.
set -euo pipefail
cd "$(dirname "$0")"
GH="$HOME/android-dev/tools/gh"
V=$(cat VERSION)
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first."; exit 1; }
./build.sh
git tag "v$V"
git push origin main "v$V"
"$GH" release create "v$V" build/cubehome.apk --title "Cube Home $V" --notes "${1:-Cube Home $V}"
echo $((V + 1)) > VERSION
git commit -qm "Start version $((V + 1))" VERSION
git push -q origin main
echo "Released v$V."
