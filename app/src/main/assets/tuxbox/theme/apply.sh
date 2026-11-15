#!/bin/bash
# Usage: apply.sh <fallback-width> <fallback-height> <gpu-mode>
# Installs the Hypr-style theme into ~/.config, scaled for the current screen.
T=/opt/tuxbox/theme; C=/root/.config
SW="$1"; SH="$2"; GPU="${3:-off}"
read -r W H < <(timeout 3 xrandr --current 2>/dev/null | awk '/\*/{split($1,a,"x"); print a[1], a[2]; exit}')
[ -n "$W" ] && SW=$W && SH=$H

# Everything is designed for 720p and scaled from there.
S=$(( SH * 100 / 720 )); [ "$S" -lt 100 ] && S=100
px() { echo $(( ($1 * S + 50) / 100 )); }
DPI=$(( 96 * S / 100 ))
BAR_H=$(px 30); DOCK_H=$(px 44); M=$(px 6); SIDE=$(px 10); GAP=$(px 8)
LW=$(px 250); CW=$(px 100); RW=$(px 160); DPAD=$(px 13); DSIDE=$(px 8); DW=$(( 5 * ($(px 23) + 2 * DPAD) + 2 * DSIDE ))
CX=$(( (SW - CW) / 2 )); RX=$(( SW - SIDE - RW )); DX=$(( (SW - DW) / 2 ))
TOP=$(( M + BAR_H + $(px 2) )); BOT=$(( M + DOCK_H + $(px 2) ))

# picom's GLX backend can't start on Xvnc (built-in viewer), and blur needs it.
# picom < 12 (e.g. Ubuntu 24.04's v10) renders black with GLX on the built-in X server.
PICOM_MAJOR=$(picom --version 2>/dev/null | grep -oE '[0-9]+' | head -1); PICOM_MAJOR=${PICOM_MAJOR:-0}
if [ "$GPU" = off ] || [ "${TUXBOX_DISPLAY:-vnc}" = vnc ] || [ "$PICOM_MAJOR" -lt 12 ]; then BACKEND=xrender; ACTIVE=1.0; INACTIVE=0.95
else BACKEND=glx; ACTIVE=0.92; INACTIVE=0.84; fi

fill() { sed -e "s/@BAR_H@/$BAR_H/g; s/@DOCK_H@/$DOCK_H/g; s/@RADIUS@/$(px 12)/g; s/@M@/$M/g" \
  -e "s/@SIDE@/$SIDE/g; s/@LW@/$LW/g; s/@CW@/$CW/g; s/@RW@/$RW/g; s/@DW@/$DW/g" \
  -e "s/@CX@/$CX/g; s/@RX@/$RX/g; s/@DX@/$DX/g; s/@DPI@/$DPI/g; s/@GAP@/$GAP/g" \
  -e "s/@TOP@/$TOP/g; s/@BOT@/$BOT/g; s/@BACKEND@/$BACKEND/g; s/@ACTIVE@/$ACTIVE/g; s/@INACTIVE@/$INACTIVE/g" \
  -e "s/@FOFF@/$(px 3)/g; s/@DPAD@/$DPAD/g; s/@DSIDE@/$DSIDE/g; s/@IOFF@/$(px 3)/g; s/@DOFF@/$(px 4)/g" "$1"; }

mkdir -p $C/bspwm $C/sxhkd $C/picom $C/polybar $C/rofi $C/kitty $C/dunst $C/gtk-3.0 $C/gtk-4.0 $C/tuxbox
fill $T/bspwmrc.in > $C/bspwm/bspwmrc; chmod +x $C/bspwm/bspwmrc
fill $T/polybar.ini.in > $C/polybar/config.ini
fill $T/picom.conf.in > $C/picom/picom.conf
cp $T/sxhkdrc $C/sxhkd/sxhkdrc
cp $T/tuxbox.rasi $C/rofi/config.rasi
cp $T/kitty.conf $C/kitty/kitty.conf
cp $T/dunstrc $C/dunst/dunstrc
cp $T/gtk-settings.ini $C/gtk-3.0/settings.ini
cp $T/gtk-settings.ini $C/gtk-4.0/settings.ini
# Keep a wallpaper the user dropped in /root/android/wallpaper.jpg, else ours.
if [ -f /root/android/wallpaper.jpg ]; then cp /root/android/wallpaper.jpg $C/tuxbox/wallpaper.jpg
else cp $T/wallpaper.jpg $C/tuxbox/wallpaper.jpg; fi
# Re-layout (wallpaper, bars, padding) whenever the screen size changes, e.g. when the
# key bar or keyboard is shown/hidden in the X11 display.
cat > /usr/local/bin/tuxbox-resize-watch <<W
#!/bin/sh
last=""
while sleep 1; do
  cur=\$(timeout 2 xrandr --current 2>/dev/null | awk '/\\*/{print \$1; exit}')
  [ -z "\$cur" ] && continue
  if [ -n "\$last" ] && [ "\$cur" != "\$last" ]; then
    /opt/tuxbox/theme/apply.sh 0 0 "$GPU" >/dev/null 2>&1
    sh ~/.config/bspwm/bspwmrc >/dev/null 2>&1
  fi
  last=\$cur
done
W
chmod +x /usr/local/bin/tuxbox-resize-watch

# Terminal wrapper: kitty's text rendering breaks on VirGL, so give it CPU GL there.
cat > /usr/local/bin/tuxbox-term <<'W'
#!/bin/sh
if [ "$GALLIUM_DRIVER" = virpipe ]; then
  export GALLIUM_DRIVER=llvmpipe LIBGL_ALWAYS_SOFTWARE=1
  unset MESA_GL_VERSION_OVERRIDE
fi
exec kitty "$@"
W
chmod +x /usr/local/bin/tuxbox-term
cat > /root/.Xresources <<X
Xft.dpi: $DPI
Xft.antialias: 1
Xft.hinting: 1
Xft.hintstyle: hintslight
Xft.rgba: rgb
Xcursor.size: $(px 24)
X
if [ ! -f /usr/local/share/fonts/tuxbox/.v1 ]; then
  mkdir -p /usr/local/share/fonts/tuxbox && cp $T/fonts/*.ttf /usr/local/share/fonts/tuxbox/
  fc-cache -f >/dev/null 2>&1; touch /usr/local/share/fonts/tuxbox/.v1
fi
echo "[tuxbox] Hypr-style theme applied for ${SW}x${SH} (scale ${S}%, picom $BACKEND)"
