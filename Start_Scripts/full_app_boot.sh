#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Demonstrate how to keep UI log output organized when using the combined
# launcher. The UI script will reuse these values when it runs.
LOG_BASE="${LOG_BASE:-$ROOT/logs}"
export GAUGES_LOG_DIR="${GAUGES_LOG_DIR:-$LOG_BASE/ui}"
export GAUGES_LOG_MASTER="${GAUGES_LOG_MASTER:-$LOG_BASE/master.log}"

# 1) Start backend in the background
"$ROOT/scripts/backend.sh" &
BACK_PID=$!

cleanup() {
  kill "$BACK_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 2) Wait for backend to come up (tries /health then /)
HOST="${BACKEND_HOST:-127.0.0.1}"
PORT="${BACKEND_PORT:-8000}"
URL="http://${HOST}:${PORT}"
echo "Waiting for backend at $URL ..."
for i in {1..60}; do
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS "$URL/health" >/dev/null 2>&1 || curl -fsS "$URL" >/dev/null 2>&1; then
      break
    fi
  else
    # If curl isn't installed, just sleep a bit
    sleep 1
    break
  fi
  sleep 0.5
done

# 3) Launch UI (dev if DEV_MODE=true)
export BACKEND_URL="${BACKEND_URL:-$URL}"
if [[ "${DEV_MODE:-false}" == "true" ]]; then
  exec "$ROOT/scripts/ui_dev.sh"
else
  exec "$ROOT/scripts/ui.sh"
fi
