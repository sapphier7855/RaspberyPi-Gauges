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
        return store.get(k);
    }

    /** Double value (NaN if missing). */
    public double getDouble(String key) {
        String k = resolveKey(key);
        return store.getDouble(k);
    }

    /** Int value (rounds the double; returns 0 if NaN/missing). */
    public int getInt(String key) {
        double v = getDouble(key);
        if (Double.isNaN(v)) return 0;
        return (int) Math.round(v);
    }

    /** Boolean value: nonzero => true; zero/NaN => false. */
    public boolean getBoolean(String key) {
        double v = getDouble(key);
        if (Double.isNaN(v)) return false;
        return Math.abs(v) > 1e-12;
    }

    /** String value:
     *  - if DataPoint.type == "text", returns status as text payload (set by DataPoint.fromUnknown)
     *  - else returns the numeric value as String (or "" if missing)
     */
    public String getString(String key) {
        IndexStore.DataPoint dp = getRaw(key);
        if (dp == null) return "";
        if ("text".equals(dp.type)) {
            return dp.status == null ? "" : dp.status;
        }
        if (Double.isNaN(dp.v)) return "";
        return String.valueOf(dp.v);
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
}


