#!/bin/bash
# Usage: flatpak.sh <family> setup|install|remove|update [app-id]
# Flatpak inside proot can install apps (OSTree downloads need no sandbox), but it can't
# *run* them: bubblewrap needs kernel namespaces Android doesn't give apps. TuxBox launches
# installed apps itself with proot (see LinuxEnv.flatpakApp), so `flatpak run` is redirected.
set -e
FAMILY="$1"; CMD="$2"; APP="$3"
export DEBIAN_FRONTEND=noninteractive
# Triggers (desktop-database, icon caches) are run through bwrap and would fail; skip them.
export FLATPAK_TRIGGERSDIR=/var/empty/tuxbox-flatpak-triggers
mkdir -p "$FLATPAK_TRIGGERSDIR"
log() { echo "[tuxbox] $*"; }

ensure_flatpak() {
  if ! command -v /usr/bin/flatpak >/dev/null; then
    log "Installing Flatpak..."
    case "$FAMILY" in
      apt)    apt-get update; apt-get install -y flatpak ;;
      pacman) pacman -Sy --noconfirm --needed flatpak ;;
      dnf)    dnf -y install flatpak ;;
      zypper) zypper --non-interactive install flatpak ;;
      apk)    apk add flatpak ;;
      xbps)   xbps-install -Sy flatpak ;;
    esac
  fi
  /usr/bin/flatpak remote-add --system --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo

  # `flatpak run` (used by app menus) -> ask TuxBox to launch the app outside this proot.
  cat > /usr/local/bin/flatpak <<'W'
#!/bin/sh
if [ "$1" = run ]; then
  shift
  app=""
  for a in "$@"; do case "$a" in -*) ;; *) app="$a"; break ;; esac; done
  [ -n "$app" ] || { echo "usage: flatpak run APP" >&2; exit 1; }
  mkdir -p /tmp/.tuxbox-launch
  echo "${app%%/*}" > "/tmp/.tuxbox-launch/$(date +%s%N)"
  exit 0
fi
FLATPAK_TRIGGERSDIR=/var/empty/tuxbox-flatpak-triggers exec /usr/bin/flatpak "$@"
W
  chmod +x /usr/local/bin/flatpak
}

fix_desktop_files() {
  # Point exported launchers at the redirecting wrapper above.
  for f in /var/lib/flatpak/exports/share/applications/*.desktop; do
    [ -e "$f" ] || continue
    sed -i 's#/usr/bin/flatpak run#/usr/local/bin/flatpak run#g' "$f"
  done
}

case "$CMD" in
  setup)   ensure_flatpak; log "Flatpak ready (Flathub added)." ;;
  install) ensure_flatpak
           log "Installing $APP from Flathub (runtimes can be large the first time)..."
           /usr/bin/flatpak install --system -y --noninteractive flathub "$APP"
           fix_desktop_files
           log "$APP installed." ;;
  remove)  /usr/bin/flatpak uninstall --system -y --noninteractive "$APP"
           /usr/bin/flatpak uninstall --system -y --noninteractive --unused || true
           log "$APP removed." ;;
  update)  ensure_flatpak
           /usr/bin/flatpak update --system -y --noninteractive
           fix_desktop_files
           log "Flatpak apps updated." ;;
  *) echo "unknown command $CMD" >&2; exit 2 ;;
esac
