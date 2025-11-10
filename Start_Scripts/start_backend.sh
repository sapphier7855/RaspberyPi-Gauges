#!/usr/bin/env bash
# Robust backend starter:
# - Creates a local venv if missing
# - Upgrades pip/setuptools/wheel
# - Installs requirements (or FastAPI + uvicorn[standard] fallback)
# - Ensures a WebSocket backend (websockets or wsproto) is installed
# - Forces uvicorn to use the websockets implementation
# - Launches uvicorn inside the venv
set -euo pipefail

# --- Configurable defaults (override via env) ---
BACKEND_DIR="${BACKEND_DIR:-$(dirname "$0")/../backend-py}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8000}"
APP_IMPORT="${APP_IMPORT:-app:app}"              # e.g., "app:app" or "pkg.module:app"
UVICORN_OPTS="${UVICORN_OPTS:---reload --ws websockets}"  # ensure WS backend explicitly

# --- Locate Python 3 ---
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "[error] Python not found. Install Python 3 first." >&2
  exit 1
fi

# --- Move to backend folder ---
cd "$BACKEND_DIR"

# --- Create venv if missing ---
if [[ ! -d .venv ]]; then
  echo "[setup] Creating virtual environment at: $BACKEND_DIR/.venv"
  "$PY" -m venv .venv
fi

# --- Activate venv ---
# shellcheck disable=SC1091
source .venv/bin/activate

# --- Upgrade build tooling ---
echo "[setup] Upgrading pip/setuptools/wheel…"
python -m pip install --upgrade pip setuptools wheel

# --- Install dependencies ---
if [[ -f requirements.txt ]]; then
  echo "[setup] Installing dependencies from requirements.txt…"
  pip install -r requirements.txt
else
  echo "[setup] requirements.txt not found. Installing FastAPI stack fallback…"
  pip install fastapi "uvicorn[standard]"
fi

# --- Ensure at least one WebSocket backend is present ---
python - <<'PY'
import importlib, sys, subprocess
missing = []
for mod in ("websockets", "wsproto"):
    try:
        importlib.import_module(mod)
    except Exception:
        missing.append(mod)
if missing:
    print(f"[setup] Installing missing WebSocket backend(s): {missing}")
    subprocess.check_call([sys.executable, "-m", "pip", "install", *missing])
else:
    print("[setup] WebSocket backend already present.")
PY

# --- Export runtime env ---
export PYTHONUNBUFFERED=1

# --- Start server ---
echo "[start] Launching uvicorn: ${APP_IMPORT} on ${HOST}:${PORT} ${UVICORN_OPTS}"
if [[ -n "${UVICORN_OPTS}" ]]; then
  exec python -m uvicorn "${APP_IMPORT}" --host "${HOST}" --port "${PORT}" ${UVICORN_OPTS}
else
  exec python -m uvicorn "${APP_IMPORT}" --host "${HOST}" --port "${PORT}"
fi


