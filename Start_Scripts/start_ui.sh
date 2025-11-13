#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/ui-java"

export DEV_MODE=false
export BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:8000}"

# Provide a concrete example of how to direct the UI logger. The Java entry
# point accepts GAUGES_LOG_DIR (session folders) and GAUGES_LOG_MASTER (the
# master log file). Callers can override these before invoking the script if a
# different layout is desired.
LOG_BASE="${LOG_BASE:-$ROOT/logs}"
export GAUGES_LOG_DIR="${GAUGES_LOG_DIR:-$LOG_BASE/ui}"
export GAUGES_LOG_MASTER="${GAUGES_LOG_MASTER:-$LOG_BASE/master.log}"

# -Ddev=false is just belt & suspenders; App.java reads ENV too
exec ./gradlew run --args="--no-dev"

