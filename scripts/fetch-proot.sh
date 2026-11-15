#!/usr/bin/env bash
# Downloads Termux's proot build for each Android ABI and repackages it as
# jniLibs (lib*.so) so the app can exec it from nativeLibraryDir.
# Requires: curl, dpkg-deb, patchelf
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.proot-work"
OUT="$ROOT/app/src/main/jniLibs"
REPO="https://packages.termux.dev/apt/termux-main"

declare -A ABI=( [aarch64]=arm64-v8a [arm]=armeabi-v7a [x86_64]=x86_64 )

for tool in curl dpkg-deb patchelf; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

rm -rf "$WORK"; mkdir -p "$WORK"

deb_url() { # $1=termux arch $2=package
  awk -v p="$2" '$1=="Package:"{cur=$2} cur==p && $1=="Filename:"{print $2; exit}' "$WORK/Packages-$1"
}

for arch in "${!ABI[@]}"; do
  abi="${ABI[$arch]}"
  echo "==> $arch ($abi)"
  curl -fsSL "$REPO/dists/stable/main/binary-$arch/Packages" -o "$WORK/Packages-$arch"
  x="$WORK/$arch"; mkdir -p "$x"
  for pkg in proot libtalloc libandroid-shmem; do
    f="$(deb_url "$arch" "$pkg")"
    [ -n "$f" ] || { echo "package $pkg not found for $arch" >&2; exit 1; }
    curl -fsSL "$REPO/$f" -o "$x/$pkg.deb"
    dpkg-deb -x "$x/$pkg.deb" "$x/root"
  done
  usr="$x/root/data/data/com.termux/files/usr"
  dst="$OUT/$abi"; rm -rf "$dst"; mkdir -p "$dst"

  cp "$usr/bin/proot" "$dst/libproot.so"
  cp "$(readlink -f "$usr/lib/libtalloc.so.2")" "$dst/libtalloc.so"
  cp "$usr/lib/libandroid-shmem.so" "$dst/libandroid-shmem.so"
  loader="$(find "$usr/libexec/proot" -name loader -type f | head -1)"
  cp "$loader" "$dst/libproot-loader.so"
  loader32="$(find "$usr/libexec/proot" -name loader32 -type f | head -1 || true)"
  [ -n "$loader32" ] && cp "$loader32" "$dst/libproot-loader32.so"

  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$dst/libproot.so"
  patchelf --remove-rpath "$dst/libproot.so"
  patchelf --set-soname libtalloc.so "$dst/libtalloc.so"
  patchelf --remove-rpath "$dst/libtalloc.so" "$dst/libandroid-shmem.so" 2>/dev/null || true
  # VirGL server (OpenGL forwarded to Android's GLES driver) — optional per ABI
  f="$(deb_url "$arch" virglrenderer-android)"
  if [ -n "$f" ]; then
    curl -fsSL "$REPO/$f" -o "$x/virgl.deb"
    dpkg-deb -x "$x/virgl.deb" "$x/virgl"
    v="$x/virgl/data/data/com.termux/files/usr"
    cp "$v/bin/virgl_test_server_android" "$dst/libvirgl_test_server.so"
    cp "$v/opt/virglrenderer-android/lib/libvirglrenderer.so" "$dst/libvirglrenderer.so"
    cp "$v/opt/virglrenderer-android/lib/libepoxy.so" "$dst/libepoxy.so"
    patchelf --remove-rpath "$dst/libvirgl_test_server.so" "$dst/libvirglrenderer.so" "$dst/libepoxy.so" 2>/dev/null || true
  else
    echo "  (no virglrenderer-android for $arch)"
  fi

  chmod 755 "$dst"/*.so
  ls -l "$dst"
done
echo "Done. jniLibs written to $OUT"
