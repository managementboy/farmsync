#!/usr/bin/env bash
# Compare the user's real farms on the device against a baseline checksum file.
# Only the farms listed in REAL_FARMS are compared; test farms (SyncTest*) are ignored.
# Usage: scripts/verify-real-farms.sh [baseline.md5]   (settings: scripts/farmsync.env)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV="$ROOT/scripts/farmsync.env"
[ -f "$ENV" ] && . "$ENV"
BASE="${1:-$ROOT/${BASELINE:?set BASELINE in scripts/farmsync.env}}"
FARMS="${REAL_FARMS:?set REAL_FARMS in scripts/farmsync.env}"
REMOTE=/sdcard/Android/data/com.chucklefish.stardewvalley/files/Saves

pattern=$(echo $FARMS | sed 's/ /|/g')
want=$(grep -E "\./($pattern)/" "$BASE" | LC_ALL=C sort -k2)
have=$(adb shell "cd $REMOTE && find $(printf './%s ' $FARMS) -type f | xargs md5sum" | tr -d '\r' | LC_ALL=C sort -k2)
if [ "$want" = "$have" ]; then
  echo "OK: real farms identical to baseline ($(echo "$have" | wc -l) files)"
else
  echo "DIFFERENCE in real farms:" >&2
  diff <(echo "$want") <(echo "$have") >&2 || true
  exit 2
fi
