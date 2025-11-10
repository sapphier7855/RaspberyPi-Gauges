package gauges.system.pipeline;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * IndexStore
 *
 * Thread-safe store for the live snapshot coming from the backend.
 * - applySnapshot(Map<String, DataPoint>) replaces/updates values
 * - (optional) applySnapshot(String) parses JSON via gauges.helpers.JsonConfig if available
 * - setOnChange(Consumer<String>) notifies with the last-updated key (or "*" for bulk)
 * - version() increments on every snapshot apply
 *
 * Debug: prints the FULL storage index every time it gets updated (using Logger if available).
 */
public final class IndexStore {

    private final ConcurrentHashMap<String, DataPoint> data = new ConcurrentHashMap<>();
    private final AtomicLong ver = new AtomicLong(0L);

    // Optional listener (e.g., for UI invalidation). Called after apply.
    private volatile Consumer<String> onChange;

    public IndexStore() {
        log("[IndexStore] constructed");
    }

    /** Optional: set a callback invoked after each update (key of last-updated or \"*\"). */
    public void setOnChange(Consumer<String> listener) {
        this.onChange = listener;
    }

    /** Current monotonically increasing version. */
    public long version() {
        return ver.get();
    }

    /** Read-only copy of the current map (linked & sorted by key for stable prints). */
    public Map<String, DataPoint> snapshot() {
        return sortedCopy(data);
    }

    /**
     * Apply a snapshot map. Replaces existing keys with new values and inserts new keys.
     * Keys not present in the incoming snapshot are retained (additive). If you prefer a
     * strict replace (where missing keys are removed), flip the 'strictReplace' flag below.
     */
    public void applySnapshot(Map<String, DataPoint> incoming) {
        Objects.requireNonNull(incoming, "incoming");

        // Change this to 'true' if you want missing keys removed on every apply.
        boolean strictReplace = false;

        if (strictReplace) {
            data.clear();
        }

        String lastKey = "*";
        for (Map.Entry<String, DataPoint> e : incoming.entrySet()) {
            String k = String.valueOf(e.getKey());
            DataPoint v = e.getValue();
            if (v == null) continue;
            data.put(k, v);
            lastKey = k;
        }

        long vnow = ver.incrementAndGet();

        // --- Debug: print the FULL storage index (sorted) every time it’s updated ---
        Map<String, DataPoint> snap = sortedCopy(data);
        log("[IndexStore][Debug] snapshot applied, size=" + snap.size() + " v=" + vnow);
        for (Map.Entry<String, DataPoint> e : snap.entrySet()) {
            log("  " + e.getKey() + "=" + e.getValue());
        }

        Consumer<String> cb = onChange;
        if (cb != null) {
            try { cb.accept(lastKey); } catch (Throwable ignore) {}
        }
    }

    /**
     * Optional overload: If present, BootCoordinator may call this directly.
     * We attempt to parse JSON via gauges.helpers.JsonConfig.parse(String). If that helper
     * isn’t available, this is a no-op (the BootCoordinator’s compat path can handle Map parsing).
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void applySnapshot(String jsonText) {
        if (jsonText == null) return;
        try {
            Class<?> jsonHelper = Class.forName("gauges.helpers.JsonConfig");
            var parseMethod = jsonHelper.getMethod("parse", String.class);
            Object any = parseMethod.invoke(null, jsonText);

            Map parsed;
            if (any instanceof Map) {
                parsed = (Map) any;
            } else {
                try {
                    var toMap = any.getClass().getMethod("toMap");
                    parsed = (Map) toMap.invoke(any);
                } catch (Throwable t) {
                    log("[IndexStore][Warn] JsonConfig.parse did not return Map and has no toMap(); ignoring String snapshot");
                    return;
                }
            }

            // Adapt parsed -> Map<String, DataPoint>
            Map<String, DataPoint> adapted = new LinkedHashMap<>();
            for (Object kObj : parsed.keySet()) {
                String k = String.valueOf(kObj);
                Object raw = parsed.get(kObj);
                DataPoint dp = DataPoint.fromUnknown(raw);
                if (dp != null) adapted.put(k, dp);
            }
            applySnapshot(adapted);

        } catch (ClassNotFoundException cnf) {
            log("[IndexStore][Warn] gauges.helpers.JsonConfig not found; applySnapshot(String) is a no-op.");
        } catch (NoSuchMethodException nsm) {
            log("[IndexStore][Warn] JsonConfig.parse(String) not found; applySnapshot(String) is a no-op.");
        } catch (Throwable t) {
            log("[IndexStore][Error] Failed to parse String snapshot: " + t.getClass().getSimpleName() + " → " + t.getMessage());
        }
    }

    /** Get a DataPoint by key (null if missing). */
    public DataPoint get(String key) {
        return data.get(key);
    }

    /** Convenience: get numeric value (NaN if missing). */
    public double getDouble(String key) {
        DataPoint dp = data.get(key);
        return dp == null ? Double.NaN : dp.v;
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static Map<String, DataPoint> sortedCopy(Map<String, DataPoint> src) {
        LinkedHashMap<String, DataPoint> out = new LinkedHashMap<>();
        src.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(k -> out.put(k, src.get(k)));
        return out;
    }

    private static void log(String msg) {
        // Prefer the central Logger if available, else fallback to stdout (which Logger captures anyway).
        try {
            Class<?> logger = Class.forName("gauges.system.Logger");
            var m = logger.getMethod("info", String.class);
            m.invoke(null, msg);
        } catch (Throwable ignore) {
            System.out.println(msg);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------------------------------------

    /**
     * DataPoint represents one live value.
     * Common shapes are supported via constructors and factories to ease interop.
     */
    public static final class DataPoint {
        public final double v;      // value
        public final long ts;       // timestamp (millis)
        public final String type;   // optional type/kind
        public final String status; // optional status

        // Constructors commonly seen across variants
        public DataPoint(double v, long ts) {
            this(v, ts, null, null);
        }
        public DataPoint(double v, long ts, String type) {
            this(v, ts, type, null);
        }
        public DataPoint(double v, long ts, String type, String status) {
            this.v = v;
            this.ts = ts;
            this.type = type;
            this.status = status;
        }

        /** Loose factory: try to adapt a variety of incoming shapes. */
        @SuppressWarnings({ "rawtypes" })
        public static DataPoint fromUnknown(Object raw) {
            if (raw == null) return null;

            // Numeric literal → (v, now)
            if (raw instanceof Number) {
                return new DataPoint(((Number) raw).doubleValue(), System.currentTimeMillis());
            }

            // Map-like {v, ts, type, status} (keys may vary: v/value/val, ts/t/time)
            if (raw instanceof Map) {
                Map m = (Map) raw;
                Object vObj   = first(m.get("v"), m.get("value"), m.get("val"));
                Object tsObj  = first(m.get("ts"), m.get("t"), m.get("time"));
                Object type   = first(m.get("type"), m.get("kind"), m.get("k"));
                Object status = first(m.get("status"), m.get("s"));

                double v = (vObj instanceof Number) ? ((Number) vObj).doubleValue() : parseDoubleOrNaN(vObj);
                long ts  = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : System.currentTimeMillis();
                return new DataPoint(v, ts, type == null ? null : String.valueOf(type),
                        status == null ? null : String.valueOf(status));
            }

            // String fallback – keep it as a text-type datapoint
            return new DataPoint(Double.NaN, System.currentTimeMillis(), "text", String.valueOf(raw));
        }

        private static Object first(Object... arr) { for (Object o : arr) if (o != null) return o; return null; }
        private static double parseDoubleOrNaN(Object o) {
            try { return (o == null) ? Double.NaN : Double.parseDouble(String.valueOf(o)); }
            catch (Exception ignore) { return Double.NaN; }
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(v);
            sb.append("@").append(ts);
            if (type != null)   sb.append(" type=").append(type);
            if (status != null) sb.append(" status=").append(status);
            return sb.toString();
        }
    }
}


