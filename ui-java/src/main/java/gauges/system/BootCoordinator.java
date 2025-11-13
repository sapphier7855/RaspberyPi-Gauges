package gauges.system;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import gauges.system.pipeline.IndexFetcher;
import gauges.system.pipeline.IndexRouter;
import gauges.system.pipeline.IndexStore;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * BootCoordinator
 *
 * Starts the data pipeline and windows. Includes endpoint probing to avoid 404s
 * when the backend uses a different path (e.g., /api/index instead of /index).
 */
public final class BootCoordinator {

    private IndexStore store;
    private IndexFetcher fetcher;

    public void start(Stage primaryStage) {
        Platform.setImplicitExit(true);
        Platform.runLater(() -> {
            try {
                startDataPipeline();

                WindowManager wm = new WindowManager(modeKey -> {
                    try { ModeController.global().load(modeKey); }
                    catch (Throwable t) { System.err.println("[Boot] Mode switch failed for: " + modeKey); t.printStackTrace(); }
                });
                wm.createAndShowAllWindows_SafeShow();

                String initialMode = System.getProperty("mode", "mode1");
                try { ModeController.global().load(initialMode); }
                catch (Throwable t) { System.err.println("[Boot] Failed to load initial mode: " + initialMode); t.printStackTrace(); }

                dumpOpenWindows("[Boot] after WindowManager");
                Runtime.getRuntime().addShutdownHook(new Thread(this::safeStopFetcher, "Shutdown-StopFetcher"));
            } catch (Throwable t) {
                System.err.println("[Boot] Fatal error during startup:");
                t.printStackTrace();
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Data pipeline
    // --------------------------------------------------------------------------------------------

    private void startDataPipeline() {
        Properties props = loadAppProperties();
        String baseUrl = prop(props, "backend.url", "http://127.0.0.1:8000");

        // Candidate paths to probe
        java.util.List<String> candidates = new java.util.ArrayList<>();
        String cliIndex  = System.getProperty("index.path", null);
        String fileIndex = props.getProperty("index.path");
        if (cliIndex != null && !cliIndex.isBlank())  candidates.add(cliIndex);
        if (fileIndex != null && !fileIndex.isBlank()) candidates.add(fileIndex);
        candidates.add("/index");
        candidates.add("/api/index");
        candidates.add("/v1/index");
        candidates.add("/data/index");
        candidates.add("/index.json");
        // normalize & de-dup
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String p : candidates) {
            if (p == null) continue;
            uniq.add(p.startsWith("/") ? p : "/" + p);
        }
        candidates = new java.util.ArrayList<>(uniq);

        int hz = intProp(props, "poll.hz", 30);
        if (hz <= 0) hz = 1;
        Duration period = Duration.ofMillis(Math.max(5, 1000 / hz));

        long timeoutMillis = longProp(props, "fetch.timeoutMillis", 1500L);
        Duration timeout = Duration.ofMillis(Math.max(250L, timeoutMillis));

        URI endpoint = probeEndpoint(baseUrl, candidates, timeout);

        store = new IndexStore();
        IndexRouter.install(store);

        fetcher = new IndexFetcher(
                endpoint,
                period,
                timeout,
                snapshot -> {
                    try { applySnapshotCompat(store, snapshot); }
                    catch (Throwable t) { System.err.println("[Pipeline] Failed to apply snapshot (compat):"); t.printStackTrace(); }
                }
        );

        try { store.setOnChange(key -> System.out.println("[IndexStore] snapshot applied (key=" + key + ") v=" + store.version())); }
        catch (Throwable ignore) { }

        try {
            fetcher.start();
            System.out.println("[Pipeline] IndexFetcher started → " + endpoint + " every " + period.toMillis() + " ms");
        } catch (Throwable t) {
            System.err.println("[Pipeline] Failed to start IndexFetcher:");
            t.printStackTrace();
        }
    }

    /** Probe a list of candidate paths and return the first that responds with 2xx. */
    private static URI probeEndpoint(String baseUrl, java.util.List<String> paths, Duration timeout) {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build();
        System.out.println("[Boot][Probe] base=" + baseUrl + " candidates=" + paths);
        for (String p : paths) {
            try {
                URI uri = URI.create(baseUrl + p);
                var req = java.net.http.HttpRequest.newBuilder(uri)
                        .method("GET", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .build();
                var start = java.time.Instant.now();
                var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                long ms = java.time.Duration.between(start, java.time.Instant.now()).toMillis();
                int code = resp.statusCode();
                System.out.println("[Boot][Probe] " + uri + " → status=" + code + " in " + ms + "ms");
                if (code >= 200 && code < 300) {
                    System.out.println("[Boot][Probe] Selected endpoint: " + uri + " (" + code + ")");
                    return uri;
                }
            } catch (Exception e) {
                System.err.println("[Boot][Probe] error for path " + p + ": " + e.getMessage());
            }
        }
        String fallback = paths.isEmpty() ? "/index" : paths.get(0);
        URI uri = URI.create(baseUrl + fallback);
        System.err.println("[Boot][Probe] No 2xx path found; falling back to " + uri);
        return uri;
    }

    /**
     * Accepts the raw JSON text from IndexFetcher and adapts it to whatever form
     * IndexStore expects (Map<String, IndexStore.DataPoint>), with a reflective fallback.
     */
    @SuppressWarnings({ "rawtypes" })
    private static void applySnapshotCompat(IndexStore store, String jsonText) throws Exception {
        // If IndexStore has an overload that accepts String, use it directly.
        try {
            var m = IndexStore.class.getMethod("applySnapshot", String.class);
            System.out.println("[Boot] Using IndexStore.applySnapshot(String) overload");
            m.invoke(store, jsonText);
            return;
        } catch (NoSuchMethodException ignore) { }

        Map parsed;
        try {
            var jsonHelper  = Class.forName("gauges.helpers.JsonConfig");
            var parseMethod = jsonHelper.getMethod("parse", String.class);
            Object any = parseMethod.invoke(null, jsonText);
            if (any instanceof Map) parsed = (Map) any; else {
                try { var toMap = any.getClass().getMethod("toMap"); parsed = (Map) toMap.invoke(any); }
                catch (Throwable t) { throw new IllegalStateException("JsonConfig.parse did not return Map and has no toMap(): " + any); }
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            parsed = (Map) java.util.Map.of();
            System.err.println("[Boot] JsonConfig not found; unable to parse snapshot into Map.\n" +
                    "       Add a String overload to IndexStore.applySnapshot or ensure JsonConfig is on classpath.");
        }

        Class<?> dpClass = Class.forName("gauges.system.pipeline.IndexStore$DataPoint");
        java.util.function.Function<Object, Object> toDataPoint = raw -> {
            try {
                if (raw == null) return null;
                if (raw instanceof Number) {
                    double v = ((Number) raw).doubleValue();
                    long ts = System.currentTimeMillis();
                    return constructDataPoint(dpClass, v, ts, null, null, raw);
                }
                if (raw instanceof Map) {
                    Map m = (Map) raw;
                    Object vObj   = firstNonNull(m.get("v"), m.get("value"), m.get("val"));
                    Object tsObj  = firstNonNull(m.get("ts"), m.get("t"), m.get("time"));
                    Object type   = firstNonNull(m.get("type"), m.get("kind"), m.get("k"));
                    Object status = firstNonNull(m.get("status"), m.get("s"));
                    double v = vObj instanceof Number ? ((Number) vObj).doubleValue() : parseDoubleOrNaN(vObj);
                    long ts = tsObj instanceof Number ? ((Number) tsObj).longValue() : System.currentTimeMillis();
                    return constructDataPoint(dpClass, v, ts, toStringOrNull(type), toStringOrNull(status), raw);
                }
                // Last resort: stringify
                return constructDataPoint(dpClass, Double.NaN, System.currentTimeMillis(), "text", null, String.valueOf(raw));
            } catch (Throwable t) {
                System.err.println("[Boot] Failed to adapt raw value into DataPoint: " + raw);
                t.printStackTrace();
                return null;
            }
        };

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Object k : parsed.keySet()) {
            Object dp = toDataPoint.apply(parsed.get(k));
            if (dp != null) out.put(String.valueOf(k), dp);
        }

        try {
            var m = IndexStore.class.getMethod("applySnapshot", java.util.Map.class);
            m.invoke(store, out);
            System.out.println("[Boot] Applied snapshot via Map adapter: size=" + out.size());
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("IndexStore.applySnapshot(Map) not found; expected signature missing.");
        }
    }

    private static Object constructDataPoint(Class<?> dpClass, double v, long ts, String type, String status, Object raw) throws Exception {
        // Try common constructor shapes
        for (var ctor : dpClass.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            try {
                if (p.length == 2 && p[0] == double.class && p[1] == long.class) { ctor.setAccessible(true); return ctor.newInstance(v, ts); }
                if (p.length == 3 && p[0] == double.class && p[1] == long.class && p[2] == String.class) { ctor.setAccessible(true); return ctor.newInstance(v, ts, type); }
                if (p.length == 4 && p[0] == double.class && p[1] == long.class && p[2] == String.class && p[3] == String.class) { ctor.setAccessible(true); return ctor.newInstance(v, ts, type, status); }
                if (p.length == 1 && p[0] == Object.class) { ctor.setAccessible(true); return ctor.newInstance(raw); }
            } catch (Throwable ignore) { }
        }
        // Try common factories: of(...), from(...), create(...)
        for (String name : new String[]{"of", "from", "create"}) {
            for (var m : dpClass.getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 2 && p[0] == double.class && p[1] == long.class) return m.invoke(null, v, ts);
                    if (p.length == 3 && p[0] == double.class && p[1] == long.class && p[2] == String.class) return m.invoke(null, v, ts, type);
                    if (p.length == 1 && p[0] == Object.class) return m.invoke(null, raw);
                    if (p.length == 1 && java.util.Map.class.isAssignableFrom(p[0])) return m.invoke(null, raw);
                } catch (Throwable ignore) { }
            }
        }
        throw new NoSuchMethodException("No compatible constructor/factory found for IndexStore.DataPoint");
    }

    private static Object firstNonNull(Object... arr) { for (Object o : arr) if (o != null) return o; return null; }
    private static String toStringOrNull(Object o) { return o == null ? null : String.valueOf(o); }
    private static double parseDoubleOrNaN(Object o) { try { return (o == null) ? Double.NaN : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return Double.NaN; } }

    private static Properties loadAppProperties() {
        Properties p = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("Application.properties")) {
            if (in != null) { p.load(in); }
            else { System.err.println("[Boot] Application.properties not found on classpath; using defaults"); }
        } catch (IOException e) {
            System.err.println("[Boot] Failed to read Application.properties; using defaults");
            e.printStackTrace();
        }
        return p;
    }

    private static String prop(Properties p, String key, String def) {
        Objects.requireNonNull(key, "key");
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        String v = p.getProperty(key);
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }

    private static int intProp(Properties p, String key, int def) {
        String s = prop(p, key, String.valueOf(def));
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return def; }
    }
    private static long longProp(Properties p, String key, long def) {
        String s = prop(p, key, String.valueOf(def));
        try { return Long.parseLong(s.trim()); } catch (Exception ignore) { return def; }
    }

    private void safeStopFetcher() {
        try { if (fetcher != null) fetcher.stop(); } catch (Throwable ignore) { }
    }

    private static void dumpOpenWindows(String tag) {
        System.out.println(tag);
        for (Window w : Window.getWindows()) {
            System.out.println("  • " + w.getClass().getName()
                    + "  showing=" + w.isShowing()
                    + "  x=" + w.getX() + " y=" + w.getY()
                    + "  w=" + w.getWidth() + " h=" + w.getHeight()
                    + (w instanceof Stage s ? "  title=\"" + safeTitle(s) + "\"" : ""));
        }
    }

    private static String safeTitle(Stage s) { try { return s.getTitle(); } catch (Throwable ignore) { return ""; } }
}


