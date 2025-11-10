from types import SimpleNamespace
import threading, time

# Holds the live value + settings
simulated = SimpleNamespace(value=0.0, low=0.0, high=180.0, speed=10.0, _running=False)

def configure(*, low: float | None = None, high: float | None = None, speed: float | None = None) -> None:
    if low   is not None: simulated.low   = float(low)
    if high  is not None: simulated.high  = float(high)
    if speed is not None and speed > 0: simulated.speed = float(speed)

def _loop():
    x = min(simulated.low, simulated.high)
    direction = 1.0
    t0 = time.monotonic()
    while simulated._running:
        now = time.monotonic(); dt = now - t0; t0 = now
        lo = min(simulated.low, simulated.high); hi = max(simulated.low, simulated.high)
        x += direction * simulated.speed * dt
        if x >= hi: x = hi - (x - hi); direction = -1.0
        elif x <= lo: x = lo + (lo - x); direction = 1.0
        simulated.value = x
        time.sleep(0.01)

def start():
    if simulated._running: return
    simulated._running = True
    threading.Thread(target=_loop, daemon=True).start()

def stop():
    simulated._running = False