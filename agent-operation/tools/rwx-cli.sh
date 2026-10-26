#!/usr/bin/env bash
# Native Linux/WSL/macOS client; no PowerShell or daemon dependency.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == send ]]; then
    [[ $# -ge 2 ]] || { echo 'Usage: rwx-cli.sh send "command" [timeout-seconds]' >&2; exit 2; }
    exec python3 "$SCRIPT_DIR/rwx-cli.py" --timeout "${3:-60}" --line "$2"
fi
if [[ "${1:-}" == status || "${1:-}" == start ]]; then
    exec python3 "$SCRIPT_DIR/rwx-cli.py" --list
fi
if [[ "${1:-}" == stop ]]; then
    echo 'The portable client has no daemon. Use rwx-disconnect or rwx-exit for RWX.'
    exit 0
fi
exec python3 "$SCRIPT_DIR/rwx-cli.py" "$@"
