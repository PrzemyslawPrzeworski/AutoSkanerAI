#!/bin/sh
# Launcher for post-edit-check.mjs. Exists for one reason: the gate must be audible when it cannot
# run at all.
#
# node runs the checker, so node cannot report its own absence. The hook this replaces was invoked
# as bare `node -e "…" || true`, and when node left PATH the shell returned 127 — which Claude Code
# logs and discards. The gate was dead from May to September with no visible symptom. Here a missing
# interpreter exits 2, which is the one code whose message reaches the agent.

# This machine keeps node outside the default PATH (nvm4w). Prepend it if it is not already there.
case ":$PATH:" in
  *:/c/nvm4w/nodejs:*) ;;
  *) PATH="/c/nvm4w/nodejs:$PATH"; export PATH ;;
esac

if ! command -v node >/dev/null 2>&1; then
  echo "[post-edit] gate DID NOT RUN: node is not on PATH, so prettier and the frontend suite were both skipped." >&2
  echo "[post-edit] Treat frontend edits as unverified until this is fixed (node lives at /c/nvm4w/nodejs here)." >&2
  exit 2
fi

exec node "$(dirname "$0")/post-edit-check.mjs"
