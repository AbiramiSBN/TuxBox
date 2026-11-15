#!/usr/bin/env bash
# Applies TuxBox's changes to Termux:X11's lorie module (controller support, host app id,
# native lib loading from the extracted lib dir, key bar hide button, ARM-only ABIs).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/third_party/termux-x11"
if git apply --check "$ROOT/patches/lorie-tuxbox.patch" 2>/dev/null; then
  git apply "$ROOT/patches/lorie-tuxbox.patch"
  echo "TuxBox patches applied."
else
  echo "Patches already applied (or upstream changed; check patches/lorie-tuxbox.patch)."
fi
