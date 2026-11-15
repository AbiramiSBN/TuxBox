#!/bin/bash
# Usage: start.sh <desktop-id> <width> <height>
# Env from the app: TUXBOX_DISPLAY=vnc|x11  TUXBOX_GPU=off|virgl|freedreno|zink
#   vnc: desktop is served by Xvnc / wayvnc on 127.0.0.1:5901 (built-in viewer)
#   x11: desktop draws on Termux:X11's X server (DISPLAY=:0, socket in /tmp/.X11-unix)
DE="$1"; W="${2:-1280}"; H="${3:-720}"
PORT=5901
export HOME=/root USER=root LOGNAME=root SHELL=/bin/bash
export XDG_RUNTIME_DIR=/tmp/runtime-root
export XDG_CONFIG_HOME=/root/.config
export NO_AT_BRIDGE=1
rm -rf "$XDG_RUNTIME_DIR"; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
log() { echo "[tuxbox] $*"; }

trap 'kill $(jobs -p) 2>/dev/null' EXIT
# LXC images ship an "uninitialized" machine-id, which D-Bus rejects.
if [ "$(tr -d '\n' < /etc/machine-id 2>/dev/null | wc -c)" -ne 32 ]; then
  (dbus-uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid | tr -d '-') > /etc/machine-id
fi
mkdir -p /var/lib/dbus; ln -sf /etc/machine-id /var/lib/dbus/machine-id
# Name Android's supplementary groups so tools stop printing "cannot find name for group ID".
for g in $(id -G 2>/dev/null); do
  grep -q "^[^:]*:[^:]*:$g:" /etc/group || echo "aid_$g:x:$g:root" >> /etc/group
done
DISPLAY_MODE="${TUXBOX_DISPLAY:-vnc}"
GPU="${TUXBOX_GPU:-off}"

# ---- GPU ------------------------------------------------------------------
case "$GPU" in freedreno|zink)
  if [ ! -e /root/.tuxbox/gpu-drivers ]; then
    log "Adreno GPU drivers not installed in this distro; using software rendering."
    GPU=off
  fi ;;
esac
if [ "$GPU" = freedreno ] && [ "$DISPLAY_MODE" = vnc ]; then
  log "Freedreno needs Termux:X11 (Xvnc has no DRI3); using Zink instead."
  GPU=zink
fi
case "$GPU" in
  freedreno) export MESA_LOADER_DRIVER_OVERRIDE=kgsl TU_DEBUG=noconform ;;
  zink)      export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink ZINK_DESCRIPTORS=lazy TU_DEBUG=noconform ;;
  virgl)
    export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.0 \
           VTEST_SOCKET_NAME=/tmp/.virgl_test VTEST_RENDERER_SOCKET_NAME=/tmp/.virgl_test
    for _ in $(seq 50); do [ -S /tmp/.virgl_test ] && break; sleep 0.1; done
    [ -S /tmp/.virgl_test ] || { log "VirGL server didn't start; using software rendering."; GPU=off; } ;;
esac
[ "$GPU" = off ] && export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
case "$GPU" in freedreno|zink)
  export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json ;;
esac
log "Display: $DISPLAY_MODE   GPU: $GPU"

# ---- X11 ------------------------------------------------------------------
x_session() {
  export XDG_SESSION_TYPE=x11
  if [ "$DISPLAY_MODE" = x11 ]; then
    export DISPLAY=:0
    log "Waiting for Termux:X11..."
    for _ in $(seq 150); do [ -S /tmp/.X11-unix/X0 ] && break; sleep 0.2; done
    [ -S /tmp/.X11-unix/X0 ] || { log "Termux:X11 server did not start."; exit 1; }
  else
    export DISPLAY=:1
    rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
    mkdir -p /tmp/.X11-unix; chmod 1777 /tmp/.X11-unix
    log "Starting Xvnc ${W}x${H} on port $PORT"
    # The X server must not see the GPU settings meant for apps: with Zink it opens
    # the Adreno GPU itself and stops answering clients.
    env -u MESA_LOADER_DRIVER_OVERRIDE -u GALLIUM_DRIVER -u ZINK_DESCRIPTORS -u TU_DEBUG \
        -u VK_ICD_FILENAMES -u MESA_GL_VERSION_OVERRIDE -u VTEST_SOCKET_NAME LIBGL_ALWAYS_SOFTWARE=1 \
    Xvnc :1 -geometry "${W}x${H}" -depth 24 -rfbport $PORT -localhost \
         -SecurityTypes None -AlwaysShared -ac -dpi 120 &
    for _ in $(seq 100); do [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.1; done
  fi
  log "Launching: $*"
  dbus-launch --exit-with-session "$@"
}

wayland_session() { # $1 = sway config file
  export XDG_SESSION_TYPE=wayland WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 \
         WLR_RENDERER=pixman WLR_NO_HARDWARE_CURSORS=1
  log "Starting headless Sway (${W}x${H}) + wayvnc on port $PORT"
  dbus-launch --exit-with-session sway --unsupported-gpu -c "$1"
}

mkdir -p /root/.config/tuxbox
case "$DE" in
  xfce)      x_session xfce4-session ;;
  lxde)      x_session startlxde ;;
  lxqt)      x_session startlxqt ;;
  mate)      x_session mate-session ;;
  cinnamon)  export XDG_CURRENT_DESKTOP=X-Cinnamon; x_session cinnamon-session ;;
  kde)       export KDE_FULL_SESSION=true XDG_CURRENT_DESKTOP=KDE; x_session startplasma-x11 ;;
  gnome)     export XDG_CURRENT_DESKTOP=GNOME
             x_session gnome-session --builtin --session=gnome ;;
  i3)        x_session i3 ;;
  hyprstyle) x_session bash -c '/opt/tuxbox/theme/apply.sh "$0" "$1" "$2"; exec bspwm' "$W" "$H" "$GPU" ;;
  sway)
    cat > /root/.config/tuxbox/sway.conf <<CONF
include /etc/sway/config
output HEADLESS-1 resolution ${W}x${H}
exec wayvnc 127.0.0.1 $PORT
CONF
    wayland_session /root/.config/tuxbox/sway.conf ;;
  *) echo "unknown desktop $DE" >&2; exit 2 ;;
esac
log "Desktop session ended."
