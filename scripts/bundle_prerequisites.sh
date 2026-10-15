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
VERSION="$(tar -xOf "$WORK/index.tar.gz" APKINDEX | awk '/^P:apk-tools-static$/ {found=1; next} found && /^V:/ {print substr($0,3); found=0}')"
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
  ca-certificates ca-certificates-bundle bash curl git python3 py3-pip nodejs npm build-base linux-headers openssl ripgrep
# Capture base runtime so upgrades can receive media dependencies without replacing user data.
python3 "$ROOT/scripts/media_runtime_pack.py" snapshot "$WORK/rootfs" "$WORK/media-runtime.bin.json"
SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}" "$WORK/sbin/apk.static" \
  --root "$WORK/rootfs" --arch aarch64 --no-scripts add --no-cache ffmpeg py3-requests
mkdir -p "$WORK/media-wheels"
python3 -m pip download --no-deps --only-binary=:all: --dest "$WORK/media-wheels" \
  yt-dlp==2026.8.19 yt-dlp-ejs==0.8.0 youtube-transcript-api==1.2.4
python3 - "$WORK" <<'PYMEDIA'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
sites=list((root/'rootfs/usr/lib').glob('python3.*/site-packages'))
assert len(sites)==1, 'Expected one guest Python site-packages'
for wheel in (root/'media-wheels').glob('*.whl'):
 with zipfile.ZipFile(wheel) as z:
  assert not any(n.endswith(('.so','.dll','.dylib')) for n in z.namelist()), 'Media wheels must be pure Python'
  z.extractall(sites[0])
PYMEDIA
python3 "$ROOT/scripts/media_runtime_pack.py" pack "$WORK/rootfs" "$WORK/media-runtime.bin"
cp "$WORK/media-runtime.bin" "$(dirname "$ASSET")/media-runtime.bin"
for executable in node npm python3 git curl; do
  test -e "$WORK/rootfs/usr/bin/$executable" || test -L "$WORK/rootfs/usr/bin/$executable"
done
# Bundle the pinned server, its JS dependencies and both SQLite engines. Install
# scripts are skipped on the host; better-sqlite3 ships the ARM64 musl prebuild.
command -v npm >/dev/null
npm install --global --prefix "$WORK/rootfs/usr" --ignore-scripts --no-audit --no-fund 9router@0.5.99
npm install --prefix "$WORK/rootfs/usr/lib/hermes-9router-runtime" --ignore-scripts --no-audit --no-fund \
  better-sqlite3@13.0.3 sql.js@1.14.1
test -f "$WORK/rootfs/usr/lib/node_modules/9router/app/custom-server.js"
test -f "$WORK/rootfs/usr/lib/hermes-9router-runtime/node_modules/better-sqlite3/prebuilds/linuxmusl-arm64.node"
test -f "$WORK/rootfs/usr/lib/hermes-9router-runtime/node_modules/sql.js/dist/sql-wasm.wasm"
# A separate archive can update the router on existing phones without deleting
# their Linux rootfs, user files or /root/.9router account/database settings.
tar --numeric-owner --owner=0 --group=0 -czf "$(dirname "$ASSET")/router-runtime.bin" -C "$WORK/rootfs" \
  usr/bin/9router usr/lib/node_modules/9router usr/lib/hermes-9router-runtime
rm -rf "$WORK/rootfs/usr/bin/9router" "$WORK/rootfs/usr/lib/node_modules/9router" "$WORK/rootfs/usr/lib/hermes-9router-runtime"
printf '1\n' > "$WORK/rootfs/etc/hermes-prerequisites-version"
tar --numeric-owner --owner=0 --group=0 -czf "$ASSET" -C "$WORK/rootfs" .
echo 'Bundled ARM64 prerequisites into sandbox payload'
