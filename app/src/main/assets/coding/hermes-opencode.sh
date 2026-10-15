#!/bin/sh
# Official OpenCode CLI launcher for Hermes Alpine/PRoot.
# Login happens in OpenCode's own interactive CLI; never paste credentials into Hermes.
set -eu
case "${1:-help}" in
  install)
    if command -v opencode >/dev/null 2>&1; then
      echo "OpenCode CLI already installed: $(command -v opencode)"
      exit 0
    fi
    echo "Installing official opencode-ai npm package. Network required."
    npm install -g opencode-ai
    opencode --version
    ;;
  login)
    command -v opencode >/dev/null 2>&1 || { echo "First run: sh /root/.hermes/coding/hermes-opencode.sh install"; exit 2; }
    echo "Official interactive authentication. Only your provider's account entitlements apply."
    exec opencode auth login
    ;;
  models)
    command -v opencode >/dev/null 2>&1 || { echo "First install the CLI."; exit 2; }
    exec opencode models
    ;;
  run)
    shift
    command -v opencode >/dev/null 2>&1 || { echo "First install the CLI."; exit 2; }
    exec opencode "$@"
    ;;
  *)
    echo "Usage: hermes-opencode {install|login|models|run [args...]}"
    echo "Free-tier eligibility is decided by OpenCode. Paid models are not unlocked by login alone."
    ;;
esac
