#!/usr/bin/env bash
# Signs the release APK for GitHub with the key handover (APK signature scheme v3 rotation):
# old household key -> release key, so copies installed from 1.0.0 update without reinstalling.
# Needs ~/.farmsync/{farmsync-household.keystore,farmsync-release.jks,release-signing.properties,
# rotation.lineage}. Usage: scripts/sign-release.sh   (after ./gradlew :app:assembleRelease)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$HOME/.farmsync"
AS=$(ls -d /opt/android-sdk/build-tools/*/ | sort -V | tail -1)apksigner
set -a; . "$D/release-signing.properties"; set +a
VER=$(grep -oP 'versionName = "\K[^"]+' "$ROOT/farmsync/app/build.gradle.kts")
IN="$ROOT/farmsync/app/build/outputs/apk/release/app-release.apk"
BUILT=$(ls -d /opt/android-sdk/build-tools/*/ | sort -V | tail -1)aapt2
GOT=$("$BUILT" dump badging "$IN" | grep -oP "versionName='\K[^']+")
[ "$GOT" = "$VER" ] || { echo "app-release.apk is version $GOT, expected $VER: build it first" >&2; exit 1; }
OUT="$ROOT/farmsync/app/build/outputs/apk/release/farm-sync-$VER.apk"
"$AS" sign --out "$OUT" \
  --ks "$D/farmsync-household.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --next-signer --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" \
  --lineage "$D/rotation.lineage" "$IN" 2> >(grep -v WARNING >&2)
"$AS" verify --verbose --print-certs "$OUT" 2>/dev/null | grep -E "Verified using|Signer #|SHA-256" | sed 's/^/  /'
echo "Signed: $OUT"
