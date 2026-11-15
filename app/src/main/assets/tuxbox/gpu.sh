#!/bin/bash
# Usage: gpu.sh <family> <archive-in-/tmp>
# Installs the Turnip/Freedreno/Zink Mesa build on top of the distro's Mesa.
set -e
FAMILY="$1"; ARCHIVE="$2"
export DEBIAN_FRONTEND=noninteractive
log() { echo "[tuxbox] $*"; }
overlay() { log "Overlaying Adreno Mesa build..."; tar -xzf "$ARCHIVE" -C / --no-same-owner; }

log "Installing GL/Vulkan runtime and test tools..."
case "$FAMILY" in
  apt)
    apt-get update
    apt-get install -y libgl1 libegl1 libgles2 libglx-mesa0 libegl-mesa0 libgl1-mesa-dri libgbm1 \
        mesa-vulkan-drivers libvulkan1 vulkan-tools mesa-utils libva2 xkb-data
    overlay
    # Keep apt upgrades from replacing the Adreno drivers.
    for p in libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 mesa-vulkan-drivers mesa-libgallium; do
      dpkg -s "$p" >/dev/null 2>&1 && apt-mark hold "$p" >/dev/null
    done ;;
  pacman)
    pacman -Syu --noconfirm --needed vulkan-icd-loader vulkan-tools mesa-utils libva xkeyboard-config
    d=/tmp/tuxbox-mesa; rm -rf "$d"; mkdir -p "$d"; tar -xf "$ARCHIVE" -C "$d"
    log "Installing Adreno Mesa packages..."
    pacman -U --noconfirm "$d"/mesa-1*.pkg.tar.* "$d"/vulkan-freedreno-*.pkg.tar.* "$d"/vulkan-mesa-*layers-*.pkg.tar.*
    grep -q '^IgnorePkg.*mesa' /etc/pacman.conf || \
      sed -i '/^\[options\]/a IgnorePkg = mesa vulkan-freedreno vulkan-mesa-layers vulkan-mesa-implicit-layers' /etc/pacman.conf
    rm -rf "$d" ;;
  dnf)
    dnf -y install mesa-dri-drivers mesa-libEGL mesa-libGL mesa-libgbm mesa-vulkan-drivers vulkan-loader \
        vulkan-tools glx-utils libva
    overlay
    grep -q '^excludepkgs=mesa' /etc/dnf/dnf.conf || echo 'excludepkgs=mesa-*' >> /etc/dnf/dnf.conf ;;
  zypper)
    zypper --non-interactive install Mesa Mesa-dri Mesa-libEGL1 Mesa-libGL1 Mesa-gallium libgbm1 libvulkan1 \
        vulkan-tools Mesa-demo-x libva2
    overlay
    zypper --non-interactive addlock 'Mesa*' libgbm1 || true ;;
  apk)
    apk add mesa-dri-gallium mesa-egl mesa-gl mesa-gles mesa-gbm vulkan-loader vulkan-tools mesa-utils libva
    overlay ;;
  xbps)
    xbps-install -Sy mesa-dri vulkan-loader Vulkan-Tools glxinfo libglvnd libva
    overlay
    xbps-pkgdb -m hold mesa-dri || true ;;
esac
command -v ldconfig >/dev/null && ldconfig 2>/dev/null || true
rm -f "$ARCHIVE"
mkdir -p /root/.tuxbox && touch /root/.tuxbox/gpu-drivers
log "GPU drivers installed. Test with: vkcube, glxinfo -B, glxgears"
