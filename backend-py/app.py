from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from sources.fixed import static
from sources.simulate import simulated, start as sim_start

app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Mode selection: "static" or "simulated"
MODE = "simulated"   # change as needed

if MODE == "simulated":
    sim_start()
    current_value = simulated
elif MODE == "static":
    current_value = static
else:
    raise RuntimeError("MODE must be 'static' or 'simulated'")


# --- Registry of values (functions that return a float or int) ---
# Add new keys here as you grow the system
value_sources = {
    "current": lambda: float(current_value.value),
    # "rpm": lambda: float(rpm_source.value),
    # "throttle": lambda: float(throttle_source.value),
}


@app.get("/health")
def health():
    return {"status": "ok", "mode": MODE, "keys": list(value_sources.keys())}


@app.get("/api/snapshot")
def snapshot():
    """Return a dictionary of all values."""
    out = {}
    for key, fn in value_sources.items():
        try:
            out[key] = fn()
        except Exception as e:
            out[key] = None  # gracefully handle errors
    return out


@app.get("/api/value/{key}")
def get_value(key: str):
    """Optional: fetch a single value (for debugging/testing)."""
    fn = value_sources.get(key)
    if not fn:
        raise HTTPException(status_code=404, detail=f"Unknown key: {key}")
    try:
        return {key: fn()}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


