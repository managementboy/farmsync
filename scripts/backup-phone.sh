#!/usr/bin/env bash
# Pull all Stardew saves from the connected Android device into a dated folder
# and verify every file against the device with md5 checksums.
# Usage: scripts/backup-phone.sh [label]   (label defaults to phone-S26Ultra)
set -euo pipefail
LABEL="${1:-phone-S26Ultra}"
REMOTE=/sdcard/Android/data/com.chucklefish.stardewvalley/files/Saves
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/backups/$LABEL/$(date +%Y-%m-%d_%H%M%S)"

adb get-state >/dev/null 2>&1 || { echo "No adb device connected" >&2; exit 1; }
mkdir -p "$DEST"
adb pull -a "$REMOTE" "$DEST/" >/dev/null

cd "$DEST/Saves"
find . -type f | LC_ALL=C sort | xargs md5sum | LC_ALL=C sort -k2 > ../checksums.md5
adb shell "cd $REMOTE && find . -type f | xargs md5sum" | tr -d '\r' | LC_ALL=C sort -k2 > ../device.md5
if cmp -s ../checksums.md5 ../device.md5; then
  rm ../device.md5
  echo "OK: $(wc -l < ../checksums.md5) files verified -> $DEST"
else
  echo "MISMATCH between backup and device:" >&2
  diff ../checksums.md5 ../device.md5 >&2 || true
  exit 2
fi
