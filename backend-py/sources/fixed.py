from types import SimpleNamespace

# Holds the live value
static = SimpleNamespace(value=42.0)

def set_value(v) -> None:
    static.value = float(v)