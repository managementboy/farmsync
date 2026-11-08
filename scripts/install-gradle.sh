#!/usr/bin/env bash
# System-wide Gradle in /opt/gradle, linked as /usr/local/bin/gradle (shadows the old apt gradle 4.4.1).
# Keeps the downloaded zip so project wrappers can point at it (file://) instead of downloading again.
# Usage: sudo bash scripts/install-gradle.sh [version]   (default 9.9.0)
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Run with sudo" >&2; exit 1; }
V="${1:-9.9.0}"
DIR=/opt/gradle
ZIP="$DIR/gradle-$V-bin.zip"
mkdir -p "$DIR"
if [ ! -f "$ZIP" ]; then
  curl -fL --progress-bar -o "$ZIP.part" "https://services.gradle.org/distributions/gradle-$V-bin.zip"
  curl -fsSL "https://services.gradle.org/distributions/gradle-$V-bin.zip.sha256" -o "$ZIP.sha256"
  echo "$(cat "$ZIP.sha256")  $ZIP.part" | sha256sum -c -
  mv "$ZIP.part" "$ZIP"
fi
[ -d "$DIR/gradle-$V" ] || unzip -q "$ZIP" -d "$DIR"
ln -sfn "$DIR/gradle-$V" "$DIR/current"
ln -sf "$DIR/current/bin/gradle" /usr/local/bin/gradle
chmod -R a+rX "$DIR"
if [ -n "${SUDO_USER:-}" ]; then setfacl -R -m u:"$SUDO_USER":rwX -m d:u:"$SUDO_USER":rwX "$DIR"; fi
if command -v flatpak >/dev/null && flatpak info --system com.google.AndroidStudio >/dev/null 2>&1; then
  flatpak override --system com.google.AndroidStudio --filesystem="$DIR"
fi
echo "Gradle $V -> $DIR/current ; $(JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /usr/local/bin/gradle --version | grep '^Gradle')"
