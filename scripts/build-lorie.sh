#!/usr/bin/env bash
# Builds the embedded X server/display (Termux:X11's "lorie" module, GPL-3.0) as an AAR
# with TuxBox's package id, and copies it to app/libs/lorie.aar.
# Requires: Android SDK + NDK 29.0.14206865, JDK 17. Source: third_party/termux-x11 (git clone --recursive).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/termux-x11"
[ -d "$SRC/lorie" ] || git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/termux/termux-x11.git "$SRC"
# lorie takes APPLICATION_ID (used for the X server -> activity broadcast) from its host app.
sed -i 's/applicationId "com.termux.x11"/applicationId "dev.tuxbox"/' "$SRC/lorie-app/build.gradle"
(cd "$SRC" && ./gradlew --no-daemon :lorie:assembleDebug)
mkdir -p "$ROOT/app/libs"
cp "$SRC/lorie/build/outputs/aar/lorie-debug.aar" "$ROOT/app/libs/lorie.aar"
echo "Wrote app/libs/lorie.aar"
