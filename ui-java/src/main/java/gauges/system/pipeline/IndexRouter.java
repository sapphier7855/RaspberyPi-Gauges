package gauges.system.pipeline;

import java.util.Objects;

import gauges.system.Logger;

/**
 * IndexRouter
 *
 * Thin convenience layer over IndexStore for UI/gauge bindings.
 * - Global singleton via install()/global()
 * - isReady() becomes true once the store has applied at least one snapshot
 * - Raw access (getRaw) and typed getters (getDouble/getInt/getBoolean/getString)
 *
 * Keys are used as-is (e.g., "current.value"). If you need aliasing, add it in resolveKey().
 */
public final class IndexRouter {

    // --------------------------------------------------------------------------------------------
    // Singleton wiring
    // --------------------------------------------------------------------------------------------
    private static volatile IndexRouter GLOBAL;

    private static final PipelineDebugLog PIPELINE_LOG = PipelineDebugLog.shared();

    public static void install(IndexStore store) {
        GLOBAL = new IndexRouter(store);
        log("[IndexRouter] installed");
    }

    public static IndexRouter global() {
        IndexRouter g = GLOBAL;
        if (g == null) {
            throw new IllegalStateException("IndexRouter not installed. Call IndexRouter.install(store) during boot.");
        }
        return g;
    }

    // --------------------------------------------------------------------------------------------
    // Instance
    // --------------------------------------------------------------------------------------------
    private final IndexStore store;

    public IndexRouter(IndexStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Ready once at least one snapshot has been applied. */
    public boolean isReady() {
        return store.version() > 0;
    }

    // --------------------------------------------------------------------------------------------
    // Accessors
    // --------------------------------------------------------------------------------------------

    /** Raw DataPoint (or null). */
    public IndexStore.DataPoint getRaw(String key) {
        String k = resolveKey(key);
        IndexStore.DataPoint dp = store.get(k);
        logAccess("getRaw", k, dp);
        return dp;
    }

    /** Double value (NaN if missing). */
    public double getDouble(String key) {
        String k = resolveKey(key);
        double value = store.getDouble(k);
        logAccess("getDouble", k, value);
        return value;
    }

    /** Int value (rounds the double; returns 0 if NaN/missing). */
    public int getInt(String key) {
        String k = resolveKey(key);
        double raw = store.getDouble(k);
        int value = Double.isNaN(raw) ? 0 : (int) Math.round(raw);
        logAccess("getInt", k, value + " (raw=" + raw + ")");
        return value;
    }

    /** Boolean value: nonzero => true; zero/NaN => false. */
    public boolean getBoolean(String key) {
        String k = resolveKey(key);
        double raw = store.getDouble(k);
        boolean result = !Double.isNaN(raw) && Math.abs(raw) > 1e-12;
        logAccess("getBoolean", k, result + " (raw=" + raw + ")");
        return result;
    }

    /** String value:
     *  - if DataPoint.type == "text", returns status as text payload (set by DataPoint.fromUnknown)
     *  - else returns the numeric value as String (or "" if missing)
     */
    public String getString(String key) {
        String k = resolveKey(key);
        IndexStore.DataPoint dp = store.get(k);
        if (dp == null) {
            String value = "";
            logAccess("getString", k, value + " (missing)");
            return value;
        }
        if ("text".equals(dp.type)) {
            String value = dp.status == null ? "" : dp.status;
            logAccess("getString", k, value + " (type=text)");
            return value;
        }
        if (Double.isNaN(dp.v)) {
            String value = "";
            logAccess("getString", k, value + " (NaN)");
            return value;
        }
        String value = String.valueOf(dp.v);
        logAccess("getString", k, value + " (type=" + dp.type + ")");
        return value;
    }

    // --------------------------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------------------------

    /** Simple pass-through for now; place aliasing rules here if you need them later. */
    private static String resolveKey(String key) {
        if (key == null) return "";
        // Example aliasing (disabled by default):
        // if (key.endsWith(".value")) return key.substring(0, key.length() - ".value".length());
        return key;
    }

    private static void log(String msg) {
        try {
            Logger.info(msg);
        } catch (Throwable ignore) {
            System.out.println(msg);
        }
    }

    private static void logAccess(String method, String key, Object value) {
        String message = "[IndexRouter][" + method + "] " + key + " -> " + String.valueOf(value);
        PIPELINE_LOG.info(message);
    }
}


