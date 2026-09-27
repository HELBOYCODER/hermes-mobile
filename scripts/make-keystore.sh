#!/usr/bin/env bash
set -euo pipefail

KEY_ALIAS="${KEY_ALIAS:-hermes}"
KEYSTORE_FILE="${KEYSTORE_FILE:-release.keystore}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"

keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

base64 < "$KEYSTORE_FILE" > "$KEYSTORE_FILE.b64"

echo
echo "Done. Add these repo secrets (Settings -> Secrets and variables -> Actions):"
echo "  KEYSTORE_BASE64   = contents of $KEYSTORE_FILE.b64 (single line)"
echo "  KEYSTORE_PASSWORD = $KEYSTORE_PASSWORD"
echo "  KEY_ALIAS         = $KEY_ALIAS"
echo "  KEY_PASSWORD      = $KEYSTORE_PASSWORD"
echo "  HERMES_URL        = https://your-hermes-webui.example.com"
