#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/ui-java"

export DEV_MODE=false
export BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:8000}"

# -Ddev=false is just belt & suspenders; App.java reads ENV too
exec ./gradlew run --args="--no-dev"

