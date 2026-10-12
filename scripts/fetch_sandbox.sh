#!/usr/bin/env bash
#
# Fetch the on-device Linux sandbox payload (no Termux app required at runtime):
#
#   app/src/main/jniLibs/arm64-v8a/libproot.so          PRoot (Termux build)
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so   PRoot ELF loader (64-bit)
#   app/src/main/jniLibs/arm64-v8a/libproot-loader32.so PRoot ELF loader (32-bit, optional)
#   app/src/main/jniLibs/arm64-v8a/libtalloc.so         talloc, proot's only dependency
#   app/src/main/assets/sandbox/alpine-rootfs.bin    Alpine Linux aarch64 minirootfs
#
# Why jniLibs: Android 10+ forbids executing anything under the app's data dir
# (W^X for untrusted_app). Only files the installer extracts into
# nativeLibraryDir from lib/<abi>/*.so are executable, so proot and its loader
# ship there with a .so suffix even though they are executables. The loader then
# maps guest binaries from the rootfs itself instead of going through execve.
#
# Termux binaries link against libtalloc.so.2 and carry a RUNPATH into Termux's
# prefix; we rewrite both with patchelf so they resolve from nativeLibraryDir.
#
# Requires: curl, ar (binutils), tar, xz, patchelf (pip install patchelf).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/app/src/main/assets/sandbox"
WORK="${TMPDIR:-/tmp}/hermes-sandbox-fetch"

TERMUX_REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
ALPINE_BRANCH="${ALPINE_BRANCH:-v3.21}"
ALPINE_RELEASE="${ALPINE_RELEASE:-3.21.3}"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

for tool in curl ar tar patchelf; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

mkdir -p "$JNI" "$ASSETS" "$WORK"

if [ -f "$JNI/libproot.so" ] && [ -f "$JNI/libproot-loader.so" ] && [ -f "$JNI/libtalloc.so" ] \
   && [ -f "$JNI/libandroid-shmem.so" ] \
   && [ -f "$ASSETS/alpine-rootfs.bin" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "sandbox payload already present (FORCE=1 to refetch)"
  exit 0
fi

echo "==> Resolving Termux package versions"
curl -fsSL "$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages.gz" -o "$WORK/Packages.gz"
gzip -dc "$WORK/Packages.gz" > "$WORK/Packages"
pkg_path() {
  awk -v p="$1" '
    $1=="Package:" {cur=$2}
    $1=="Filename:" && cur==p {print $2; exit}' "$WORK/Packages"
}
PROOT_DEB="$(pkg_path proot)"
TALLOC_DEB="$(pkg_path libtalloc)"
SHMEM_DEB="$(pkg_path libandroid-shmem)"
[ -n "$PROOT_DEB" ] && [ -n "$TALLOC_DEB" ] && [ -n "$SHMEM_DEB" ] || { echo "could not resolve termux packages" >&2; exit 1; }
echo "    proot:     $PROOT_DEB"
echo "    libtalloc: $TALLOC_DEB"
echo "    shmem:     $SHMEM_DEB"

extract_deb() { # url dest
  local deb="$WORK/$(basename "$1")"
  curl -fsSL "$TERMUX_REPO/$1" -o "$deb"
  rm -rf "$2" && mkdir -p "$2"
  (cd "$2" && ar x "$deb" && tar xf data.tar.* )
}
extract_deb "$PROOT_DEB" "$WORK/proot"
extract_deb "$TALLOC_DEB" "$WORK/talloc"
extract_deb "$SHMEM_DEB" "$WORK/shmem"

P="$WORK/proot/data/data/com.termux/files/usr"
T="$WORK/talloc/data/data/com.termux/files/usr"
S="$WORK/shmem/data/data/com.termux/files/usr"

install -m 0755 "$P/bin/proot" "$JNI/libproot.so"
install -m 0755 "$P/libexec/proot/loader" "$JNI/libproot-loader.so"
if [ -f "$P/libexec/proot/loader32" ]; then
  install -m 0755 "$P/libexec/proot/loader32" "$JNI/libproot-loader32.so"
fi
TALLOC_SO="$(ls "$T"/lib/libtalloc.so.2* | grep -v '\.so\.2$' | head -1 || true)"
[ -n "$TALLOC_SO" ] || TALLOC_SO="$T/lib/libtalloc.so.2"
install -m 0644 "$(readlink -f "$TALLOC_SO")" "$JNI/libtalloc.so"
install -m 0644 "$(readlink -f "$S/lib/libandroid-shmem.so")" "$JNI/libandroid-shmem.so"

echo "==> Patching ELF dependencies"
# Normalise every versioned NEEDED (libfoo.so.2 -> libfoo.so) and drop Termux RUNPATHs.
for so in libproot.so libtalloc.so libandroid-shmem.so; do
  f="$JNI/$so"
  patchelf --remove-rpath "$f" || true
  for need in $(patchelf --print-needed "$f"); do
    base="${need%%.so*}.so"
    [ "$need" != "$base" ] && patchelf --replace-needed "$need" "$base" "$f"
  done
done
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
patchelf --set-soname libandroid-shmem.so "$JNI/libandroid-shmem.so"
for so in libproot.so libtalloc.so libandroid-shmem.so; do
  echo "    $so needs: $(patchelf --print-needed "$JNI/$so" | tr '\n' ' ')"
done

echo "==> Downloading Alpine $ALPINE_RELEASE aarch64 minirootfs"
curl -fsSL "$ALPINE_URL" -o "$ASSETS/alpine-rootfs.bin"
echo "$ALPINE_RELEASE" > "$ASSETS/VERSION"

ls -l "$JNI" "$ASSETS"
echo "sandbox payload ready"
