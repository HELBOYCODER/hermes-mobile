#!/usr/bin/env bash
# Cross-install ARM64 runtime/build dependencies into the APK's Linux payload.
# No phone-side Termux, host Python/Node, Docker, root or emulation required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSET="$ROOT/app/src/main/assets/sandbox/alpine-rootfs.bin"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ALPINE_BRANCH="${ALPINE_BRANCH:-v3.21}"
case "$(uname -m)" in x86_64) HOST_ARCH=x86_64 ;; aarch64|arm64) HOST_ARCH=aarch64 ;; *) echo 'Unsupported build host' >&2; exit 1 ;; esac
REPO="https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main/$HOST_ARCH"
curl -fsSL --retry 3 "$REPO/APKINDEX.tar.gz" -o "$WORK/index.tar.gz"
VERSION="$(tar -xOf "$WORK/index.tar.gz" APKINDEX | awk '/^P:apk-tools-static$/ {found=1; next} found && /^V:/ {print substr($0,3); exit}')"
test -n "$VERSION"
curl -fsSL --retry 3 "$REPO/apk-tools-static-$VERSION.apk" -o "$WORK/apk.apk"
tar --warning=no-unknown-keyword -xzf "$WORK/apk.apk" -C "$WORK" sbin/apk.static
mkdir "$WORK/rootfs"
tar --no-same-owner -xzf "$ASSET" -C "$WORK/rootfs"
# Package install scripts cannot execute guest ARM64 binaries on a build host.
# Executables/libraries and their declared dependencies are all installed here;
# certificate generation is performed by the one-tap on-device installer.
SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}" "$WORK/sbin/apk.static" \
  --root "$WORK/rootfs" --arch aarch64 --no-scripts add --no-cache \
  ca-certificates ca-certificates-bundle bash curl git python3 py3-pip nodejs npm build-base linux-headers openssl
for executable in node npm python3 git curl; do
  test -e "$WORK/rootfs/usr/bin/$executable" || test -L "$WORK/rootfs/usr/bin/$executable"
done
printf '1\n' > "$WORK/rootfs/etc/hermes-prerequisites-version"
tar --numeric-owner --owner=0 --group=0 -czf "$ASSET" -C "$WORK/rootfs" .
echo 'Bundled ARM64 prerequisites into sandbox payload'
