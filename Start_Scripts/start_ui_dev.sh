#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/ui-java"

# App reads these (App.java)
export DEV_MODE=true
export BACKEND_URL="${BACKEND_URL:-http://127.0.0.1:8000}"

exec ./gradlew run --args="--dev"


