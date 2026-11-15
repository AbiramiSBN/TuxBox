# TuxBox

An Android app that runs a full Linux desktop without root. It uses proot plus a built-in VNC viewer.

**Distros:** Debian 13, Ubuntu 24.04, Arch Linux (rootfs images from images.linuxcontainers.org)
**Desktops:** XFCE, LXDE, LXQt, MATE, Cinnamon, KDE Plasma, GNOME, i3, Sway, Hyprland (experimental)

## How it works
1. The APK ships Termux's `proot` as `libproot.so` (Android 10+ only allows exec from nativeLibraryDir).
2. On first install the app downloads and extracts the distro rootfs into app storage.
3. `assets/tuxbox/install.sh` installs the chosen desktop with apt or pacman.
4. `assets/tuxbox/start.sh` launches it:
   - X11 desktops run on TigerVNC's `Xvnc`.
   - Sway runs headless and streams through `wayvnc`.
   - Hyprland runs nested inside headless Sway, because Android exposes no DRM.
5. Display, chosen in the app:
   - **X11 + GPU**: Termux:X11's X server, built into TuxBox. Supports Freedreno/Zink (Adreno) and DRI3.
   - **VNC**: Xvnc/wayvnc on `127.0.0.1:5901` shown by TuxBox's own RFB client (works everywhere; GPU via VirGL).

## Build (one APK: proot + VirGL + embedded X server)
```sh
./scripts/fetch-proot.sh          # proot + VirGL from Termux packages (needs curl, dpkg-deb, patchelf)
./scripts/apply-lorie-patches.sh  # TuxBox changes to Termux:X11's X server (patches/lorie-tuxbox.patch)
./scripts/build-lorie.sh          # builds the X server AAR (needs NDK 29.0.14206865) -> app/libs/lorie.aar
./gradlew assembleRelease         # -> app/build/outputs/apk/release/app-release.apk
```

## License
The embedded X server and display come from [Termux:X11](https://github.com/termux/termux-x11)
(GPL-3.0), so TuxBox as a whole is GPL-3.0.
Pushing to GitHub also builds the APK in Actions (see `.github/workflows/build.yml`).

## Controls
- Tap: left click
- Long-press: right click
- Drag: drag
- Two-finger drag: scroll
- Mouse and keyboard work over USB or Bluetooth.
- The bottom bar has Esc/Tab/Ctrl/Alt/Super/arrows/F-keys. Super is the mod key for i3, Sway and Hyprland.

## Tips
- **Android 12+** may kill heavy desktops ("phantom process killer"). To prevent this, enable
  *Developer options → Disable child process restrictions*, or run
  `adb shell settings put global settings_enable_monitor_phantom_procs false`.
- Desktops run as root inside proot.
- Your phone storage for the app appears at `/root/android`.
- Hyprland uses software OpenGL (llvmpipe). Expect it to be slow.
- GNOME isn't offered on Arch, because GNOME 49 removed the X11 session.
