package gauges.system.pipeline;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import gauges.system.Logger;

/**
 * IndexStore
 *
 * Thread-safe store for the live snapshot coming from the backend.
 * - applySnapshot(Map<String, DataPoint>) replaces/updates values
 * - applySnapshot(String) parses JSON text directly (using Jackson) and adapts it to DataPoints
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

    private static final PipelineDebugLog PIPELINE_LOG = PipelineDebugLog.shared();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_OF_OBJECT =
            new TypeReference<Map<String, Object>>() { };

    public IndexStore() {
        if (debugLoggingEnabled()) {
            log("[IndexStore] constructed");
            PIPELINE_LOG.info("[IndexStore] constructed");
        }
    }

    /**
     * Convenience overload used by BootCoordinator: accept raw JSON text, parse it into a
     * Map<String, Object>, adapt entries into DataPoint instances, then delegate to the map
     * variant. Failures are logged so the caller can see why nothing was stored.
     */
    public void applySnapshot(String jsonText) {
        if (jsonText == null) {
            PIPELINE_LOG.warn("[IndexStore] applySnapshot(String) called with null text");
            return;
        }

        String trimmed = jsonText.trim();
        if (trimmed.isEmpty()) {
            PIPELINE_LOG.warn("[IndexStore] applySnapshot(String) ignored empty snapshot");
            return;
        }

        Map<String, Object> raw;
        try {
            raw = JSON.readValue(trimmed, MAP_OF_OBJECT);
        } catch (Exception parseError) {
            PIPELINE_LOG.error("[IndexStore] failed to parse snapshot JSON", parseError);
            return;
        }

        if (raw.isEmpty()) {
            PIPELINE_LOG.warn("[IndexStore] parsed snapshot was empty");
            return;
        }

        Map<String, DataPoint> adapted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            DataPoint value = DataPoint.fromUnknown(entry.getValue());
            if (value != null) {
                adapted.put(key, value);
            } else {
                PIPELINE_LOG.warn("[IndexStore] dropped null datapoint for key=" + key);
            }
        }

        if (adapted.isEmpty()) {
            PIPELINE_LOG.warn("[IndexStore] no usable datapoints after adapting snapshot");
            return;
        }

        PIPELINE_LOG.info("[IndexStore] applying parsed snapshot entries=" + adapted.size());
        applySnapshot(adapted);
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

        if (debugLoggingEnabled()) {
            // --- Debug: print the FULL storage index (sorted) every time it’s updated ---
            Map<String, DataPoint> snap = sortedCopy(data);
            log("[IndexStore][Debug] snapshot applied, size=" + snap.size() + " v=" + vnow);
            PIPELINE_LOG.info("[IndexStore][Debug] snapshot applied, size=" + snap.size() + " v=" + vnow);
            for (Map.Entry<String, DataPoint> e : snap.entrySet()) {
                String line = "  " + e.getKey() + "=" + e.getValue();
                log(line);
                PIPELINE_LOG.info(line);
            }
        }

        Consumer<String> cb = onChange;
        if (cb != null) {
            try { cb.accept(lastKey); } catch (Throwable ignore) {}
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
        if (!debugLoggingEnabled()) {
            return;
        }
        // Prefer the central Logger if available, else fallback to stdout (which Logger captures anyway).
        try {
            Class<?> logger = Class.forName("gauges.system.Logger");
            var m = logger.getMethod("info", String.class);
            m.invoke(null, msg);
        } catch (Throwable ignore) {
            System.out.println(msg);
        }
    }

    private static boolean debugLoggingEnabled() {
        try {
            return Logger.isEnabled();
        } catch (Throwable ignore) {
            return false;
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


