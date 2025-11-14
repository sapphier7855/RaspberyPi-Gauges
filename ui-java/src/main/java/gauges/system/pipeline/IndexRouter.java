package gauges.system.pipeline;

import java.util.Objects;
import java.util.logging.Level;

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
    private static final String PIPE_CATEGORY = "data-pipline";
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
        IndexStore.DataPoint dp = store.get(k);
        logAccess("getRaw", k, dp);
        return dp;
    }

    /** Double value (NaN if missing). */
    public double getDouble(String key) {
        String k = resolveKey(key);
        double v = store.getDouble(k);
        logAccess("getDouble", k, v);
        return v;
    }

    /** Int value (rounds the double; returns 0 if NaN/missing). */
    public int getInt(String key) {
        String k = resolveKey(key);
        double v = store.getDouble(k);
        int result = Double.isNaN(v) ? 0 : (int) Math.round(v);
        logAccess("getInt", k, result);
        return result;
    }

    /** Boolean value: nonzero => true; zero/NaN => false. */
    public boolean getBoolean(String key) {
        String k = resolveKey(key);
        double v = store.getDouble(k);
        boolean result = !Double.isNaN(v) && Math.abs(v) > 1e-12;
        logAccess("getBoolean", k, result);
        return result;
    }

    /** String value:
     *  - if DataPoint.type == "text", returns status as text payload (set by DataPoint.fromUnknown)
     *  - else returns the numeric value as String (or "" if missing)
     */
    public String getString(String key) {
        String k = resolveKey(key);
        IndexStore.DataPoint dp = store.get(k);
        String result;
        if (dp == null) {
            result = "";
            logAccess("getString", k, result);
            return result;
        }
        if ("text".equals(dp.type)) {
            result = dp.status == null ? "" : dp.status;
            logAccess("getString", k, result);
            return result;
        }
        if (Double.isNaN(dp.v)) {
            result = "";
            logAccess("getString", k, result);
            return result;
        }
        result = String.valueOf(dp.v);
        logAccess("getString", k, result);
        return result;
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
        if (isLoggerActive()) {
            Logger.info(msg);
        } else {
            System.out.println(msg);
        }
    }

    private static void logAccess(String method, String key, Object value) {
        logPipeline(Level.INFO, "[router] " + method + '(' + key + ") -> " + formatValue(value));
    }

    private static String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof CharSequence) {
            return '"' + value.toString() + '"';
        }
        return value.toString();
    }

    private static void logPipeline(Level level, String message) {
        if (isLoggerActive()) {
            if (level == Level.SEVERE) {
                Logger.error(PIPE_CATEGORY, message);
            } else if (level == Level.WARNING) {
                Logger.warn(PIPE_CATEGORY, message);
            } else {
                Logger.info(PIPE_CATEGORY, message);
            }
        } else {
            if (level == Level.SEVERE) {
                System.err.println(message);
            } else {
                System.out.println(message);
            }
        }
    }

    private static boolean isLoggerActive() {
        try {
            return Logger.isActive();
        } catch (Throwable ignore) {
            return false;
        }
    }
}


