#!/usr/bin/env bash
# System-wide Android development environment for the Stardew sync app.
# Run once with sudo:   sudo bash scripts/setup-dev-env.sh [--with-studio]
#
# Installs / configures:
#   - OpenJDK 21 (apt)                         -> /usr/lib/jvm/java-21-openjdk-amd64
#   - Android SDK (Google cmdline-tools)       -> /opt/android-sdk
#       cmdline-tools;latest, platform-tools, newest platforms;android-NN, newest build-tools
#   - group "android-sdk" (you are added) so Gradle can install SDK parts without sudo
#   - /etc/profile.d/android-sdk.sh            -> ANDROID_HOME, ANDROID_SDK_ROOT, PATH for every user
#   - /usr/local/bin/adb + fastboot            -> now point into /opt/android-sdk/platform-tools
#   - removes the old standalone /opt/platform-tools
#   - optional (--with-studio): Android Studio via Flatpak, system-wide
# The Android SDK license prompt is shown to YOU; nothing is auto-accepted.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo: sudo bash $0" >&2; exit 1; }
USER_NAME="${SUDO_USER:-}"
[ -n "$USER_NAME" ] || { echo "Run via sudo from your normal user, not as root directly." >&2; exit 1; }

SDK=/opt/android-sdk
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
WITH_STUDIO=0; [ "${1:-}" = "--with-studio" ] && WITH_STUDIO=1

echo "==> 1/7 OpenJDK 21"
apt-get update -qq
apt-get install -y openjdk-21-jdk unzip curl
JAVA21=/usr/lib/jvm/java-21-openjdk-amd64

echo "==> 2/7 Group android-sdk (adds $USER_NAME)"
getent group android-sdk >/dev/null || groupadd android-sdk
usermod -aG android-sdk "$USER_NAME"

echo "==> 3/7 Android command-line tools -> $SDK"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -fL --progress-bar -o "$tmp/cmdline.zip" "$CMDLINE_ZIP_URL"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/bootstrap"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/bootstrap"
  rm -rf "$tmp"
fi
SM="$SDK/cmdline-tools/bootstrap/bin/sdkmanager"
[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ] && SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
export JAVA_HOME="$JAVA21"

echo "==> 4/7 Android SDK license (please read and accept yourself)"
"$SM" --sdk_root="$SDK" --licenses

echo "==> 5/7 SDK packages"
LIST=$("$SM" --sdk_root="$SDK" --list 2>/dev/null)
# Newer platforms are named with a dot (android-37.2); skip -beta/-ext variants.
PLATFORM=$(echo "$LIST" | grep -oE 'platforms;android-[0-9]+(\.[0-9]+)? ' | tr -d ' ' | sort -t- -k2 -V | tail -1)
BUILDTOOLS=$(echo "$LIST" | grep -oE 'build-tools;[0-9]+\.[0-9]+\.[0-9]+ ' | sort -t';' -k2 -V | tail -1 | tr -d ' ')
echo "    newest platform: $PLATFORM, newest build-tools: $BUILDTOOLS"
"$SM" --sdk_root="$SDK" --install "cmdline-tools;latest" "platform-tools" "$PLATFORM" "$BUILDTOOLS"
rm -rf "$SDK/cmdline-tools/bootstrap"

echo "==> 6/7 Permissions, environment, adb links"
chgrp -R android-sdk "$SDK"
chmod -R g+rwX "$SDK"
find "$SDK" -type d -exec chmod g+s {} +
# ACL for the installing user: works immediately, no logout needed (the group needs a new login).
setfacl -R -m u:"$USER_NAME":rwX -m d:u:"$USER_NAME":rwX "$SDK"
cat > /etc/profile.d/android-sdk.sh <<EOF
# Android SDK (system-wide), set up by scripts/setup-dev-env.sh
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export ANDROID_JAVA_HOME=$JAVA21
case ":\$PATH:" in *":$SDK/platform-tools:"*) ;; *) export PATH="\$PATH:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools";; esac
EOF
chmod 644 /etc/profile.d/android-sdk.sh
ln -sf "$SDK/platform-tools/adb" /usr/local/bin/adb
ln -sf "$SDK/platform-tools/fastboot" /usr/local/bin/fastboot
[ -d /opt/platform-tools ] && rm -rf /opt/platform-tools

echo "==> 7/7 Android Studio (optional)"
if [ "$WITH_STUDIO" = 1 ]; then
  flatpak install --system -y flathub com.google.AndroidStudio
  # The Flatpak sandbox only sees $HOME by default: give it the shared SDK.
  flatpak override --system com.google.AndroidStudio \
    --filesystem="$SDK" --env=ANDROID_HOME="$SDK" --env=ANDROID_SDK_ROOT="$SDK"
else
  echo "    skipped (re-run with --with-studio to add it)"
fi

echo
echo "Done. Log out and back in (or reboot) so the new group and variables apply."
echo "Default 'java' is unchanged; Android builds use JDK 21 via the project's Gradle settings."
