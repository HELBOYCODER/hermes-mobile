#!/usr/bin/env bash
set -euo pipefail

REPO="${1:-hermes-mobile}"
VISIBILITY="${2:-private}"
HERMES_URL="${HERMES_URL:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# HERMES_URL is optional: it only pre-fills the "Remote WebUI" screen; the native agent doesn't need it.

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required." >&2
  exit 1
fi

gh auth status >/dev/null
gh repo create "$REPO" --"$VISIBILITY" --source "$ROOT" --remote origin --push

gh secret set KEYSTORE_BASE64 < "$ROOT/release.keystore.b64"
gh secret set KEYSTORE_PASSWORD --body "$KEYSTORE_PASSWORD"
gh secret set KEY_ALIAS --body "$KEY_ALIAS"
gh secret set KEY_PASSWORD --body "$KEY_PASSWORD"
[ -n "$HERMES_URL" ] && gh secret set HERMES_URL --body "$HERMES_URL"

echo
echo "Pushed. Watch the build: gh run list -R $REPO -L 3"
