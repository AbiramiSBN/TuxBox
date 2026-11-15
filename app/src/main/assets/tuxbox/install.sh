#!/bin/bash
# Usage: install.sh <apt|pacman|dnf|zypper|apk|xbps> <desktop-id>
# Runs inside proot as (fake) root.
set -e
FAMILY="$1"; DE="$2"
export DEBIAN_FRONTEND=noninteractive
log() { echo "[tuxbox] $*"; }
nope() { echo "$DE isn't available on this distro." >&2; exit 2; }

HYPR_X="bspwm sxhkd picom rofi kitty polybar feh dunst papirus-icon-theme fontconfig"

pkgs() {
  case "$FAMILY:$DE" in
    # ---------------------------------------------------------------- apt
    apt:xfce)      echo "xfce4 xfce4-terminal xfce4-goodies" ;;
    apt:lxde)      echo "lxde-core lxterminal lxappearance" ;;
    apt:lxqt)      echo "lxqt-core qterminal pcmanfm-qt" ;;
    apt:mate)      echo "mate-desktop-environment-core mate-terminal" ;;
    apt:cinnamon)  echo "cinnamon-core gnome-terminal" ;;
    apt:kde)       echo "kde-plasma-desktop konsole dolphin" ;;
    apt:gnome)     echo "gnome-session gnome-shell gnome-terminal nautilus gnome-control-center gnome-tweaks" ;;
    apt:i3)        echo "i3 i3status dmenu xterm" ;;
    apt:sway)      echo "sway swaybg wayvnc foot" ;;
    apt:hyprstyle) echo "$HYPR_X thunar fonts-cantarell x11-xserver-utils" ;;
    # ------------------------------------------------------------- pacman
    pacman:xfce)      echo "xfce4 xfce4-goodies" ;;
    pacman:lxde)      echo "openbox lxpanel pcmanfm lxsession lxterminal lxappearance lxde-common" ;;
    pacman:lxqt)      echo "lxqt openbox breeze-icons" ;;
    pacman:mate)      echo "mate mate-terminal" ;;
    pacman:cinnamon)  echo "cinnamon gnome-terminal" ;;
    pacman:kde)       echo "plasma-desktop plasma-x11-session konsole dolphin" ;;
    pacman:i3)        echo "i3-wm i3status dmenu xterm" ;;
    pacman:sway)      echo "sway swaybg wayvnc foot" ;;
    pacman:hyprstyle) echo "$HYPR_X thunar cantarell-fonts xorg-xsetroot xorg-xrdb" ;;
    # ---------------------------------------------------------------- dnf
    dnf:xfce)      echo "xfce4-session xfwm4 xfce4-panel xfdesktop xfce4-settings xfce4-terminal Thunar" ;;
    dnf:lxde)      echo "lxsession openbox lxpanel pcmanfm lxterminal lxde-common" ;;
    dnf:lxqt)      echo "lxqt-session lxqt-panel lxqt-config pcmanfm-qt qterminal openbox" ;;
    dnf:mate)      echo "mate-session-manager mate-panel marco caja mate-terminal mate-settings-daemon" ;;
    dnf:cinnamon)  echo "cinnamon gnome-terminal" ;;
    dnf:i3)        echo "i3 i3status dmenu xterm" ;;
    dnf:sway)      echo "sway foot wayvnc" ;;
    dnf:hyprstyle) echo "$HYPR_X Thunar abattis-cantarell-fonts xsetroot xrdb" ;;
    # ------------------------------------------------------------- zypper
    zypper:xfce)      echo "xfce4-session xfwm4 xfce4-panel xfdesktop xfce4-settings-manager xfce4-terminal thunar" ;;
    zypper:lxqt)      echo "lxqt-session lxqt-panel lxqt-config pcmanfm-qt qterminal openbox" ;;
    zypper:mate)      echo "mate-session-manager mate-panel marco caja mate-terminal mate-settings-daemon" ;;
    zypper:cinnamon)  echo "cinnamon gnome-terminal" ;;
    zypper:kde)       echo "plasma6-session-x11 plasma6-desktop konsole dolphin" ;;
    zypper:i3)        echo "i3 i3status dmenu xterm" ;;
    zypper:sway)      echo "sway foot wayvnc" ;;
    zypper:hyprstyle) echo "$HYPR_X thunar cantarell-fonts xsetroot xrdb" ;;
    # ---------------------------------------------------------------- apk
    apk:xfce)      echo "xfce4 xfce4-terminal" ;;
    apk:lxqt)      echo "lxqt-desktop openbox" ;;
    apk:mate)      echo "mate-desktop-environment" ;;
    apk:kde)       echo "plasma-desktop-meta konsole dolphin" ;;
    apk:i3)        echo "i3wm i3status dmenu xterm" ;;
    apk:sway)      echo "sway foot wayvnc" ;;
    apk:hyprstyle) echo "$HYPR_X thunar font-cantarell xsetroot xrdb" ;;
    # --------------------------------------------------------------- xbps
    xbps:xfce)      echo "xfce4" ;;
    xbps:lxde)      echo "lxde" ;;
    xbps:lxqt)      echo "lxqt openbox" ;;
    xbps:mate)      echo "mate" ;;
    xbps:cinnamon)  echo "cinnamon gnome-terminal" ;;
    xbps:kde)       echo "kde-plasma konsole dolphin" ;;
    xbps:gnome)     echo "gnome-session gnome-shell gnome-terminal nautilus gnome-control-center" ;;
    xbps:i3)        echo "i3 i3status dmenu xterm" ;;
    xbps:sway)      echo "sway foot wayvnc" ;;
    xbps:hyprstyle) echo "$HYPR_X Thunar cantarell-fonts xsetroot xrdb" ;;
    *) nope ;;
  esac
}

PKGS="$(pkgs)"
case "$FAMILY" in
  apt)
    echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01tuxbox-sandbox
    echo 'Dpkg::Options { "--force-confdef"; "--force-confold"; }' > /etc/apt/apt.conf.d/02tuxbox-dpkg
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d; chmod +x /usr/sbin/policy-rc.d
    BASE="tigervnc-standalone-server tigervnc-tools dbus-x11 xkb-data x11-xserver-utils xfonts-base fonts-dejavu-core sudo locales nano"
    log "Updating package lists..."
    apt-get update
    log "Installing: $BASE $PKGS"
    apt-get install -y $BASE $PKGS || { log "Retrying after fixing broken packages..."; dpkg --configure -a || true; apt-get -f install -y; }
    apt-get clean ;;
  pacman)
    # pacman 7 sandboxing (landlock / dropping to alpm user) does not work in proot
    grep -q '^DisableSandbox' /etc/pacman.conf || sed -i '/^\[options\]/a DisableSandbox' /etc/pacman.conf
    sed -i 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf
    if [ ! -f /etc/pacman.d/gnupg/trustdb.gpg ]; then
      log "Initialising pacman keyring (this can take a few minutes)..."
      pacman-key --init; pacman-key --populate
    fi
    BASE="tigervnc dbus xkeyboard-config xorg-xrandr xorg-xauth ttf-dejavu sudo nano which"
    log "Installing: $BASE $PKGS"
    pacman -Syu --noconfirm --needed $BASE $PKGS
    pacman -Scc --noconfirm >/dev/null 2>&1 || true ;;
  dnf)
    BASE="tigervnc-server-minimal dbus-x11 xkeyboard-config dejavu-sans-fonts xrandr sudo nano"
    log "Installing: $BASE $PKGS"
    dnf -y --setopt=install_weak_deps=True install $BASE $PKGS
    dnf clean all ;;
  zypper)
    BASE="xorg-x11-Xvnc dbus-1-x11 xkeyboard-config dejavu-fonts xrandr sudo nano"
    log "Refreshing repositories..."
    zypper --non-interactive --gpg-auto-import-keys refresh
    log "Installing: $BASE $PKGS"
    zypper --non-interactive --gpg-auto-import-keys install $BASE $PKGS
    zypper clean -a ;;
  apk)
    BASE="tigervnc dbus dbus-x11 xkeyboard-config font-dejavu xrandr sudo nano bash procps"
    log "Installing: $BASE $PKGS"
    apk update
    apk add $BASE $PKGS ;;
  xbps)
    BASE="tigervnc dbus-x11 xkeyboard-config dejavu-fonts-ttf xrandr sudo nano"
    log "Updating xbps..."
    xbps-install -Syu xbps || true
    log "Installing: $BASE $PKGS"
    xbps-install -Syu $BASE $PKGS ;;
esac

# Optional extras: a browser for the Hypr-style dock. Failure here is fine.
if [ "$DE" = hyprstyle ]; then
  log "Installing a web browser (optional)..."
  case "$FAMILY" in
    apt)
      if ! apt-get install -y firefox-esr; then
        # Ubuntu's firefox/chromium packages are Snap stubs, which can't run here:
        # use Mozilla's own apt repository instead.
        apt-get remove -y chromium-browser >/dev/null 2>&1 || true
        apt-get install -y curl ca-certificates && install -d -m 0755 /etc/apt/keyrings && \
        curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg -o /etc/apt/keyrings/packages.mozilla.org.asc && \
        echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
          > /etc/apt/sources.list.d/mozilla.list && \
        printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla && \
        apt-get update && apt-get install -y firefox || true
      fi ;;
    pacman) pacman -S --noconfirm --needed firefox || true ;;
    dnf)    dnf -y install firefox || true ;;
    zypper) zypper --non-interactive install MozillaFirefox || true ;;
    apk)    apk add firefox || true ;;
    xbps)   xbps-install -y firefox || true ;;
  esac
fi

mkdir -p /root/.tuxbox
touch "/root/.tuxbox/de-$DE"
log "$DE installed. Tap Start to launch it."
