package gauges.system.windows;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gauges.system.ModeController;
import gauges.system.WindowManager;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * DashboardWindow
 * Renders all objects under dashboard.displays[] from the current mode JSON.
 * Uses ModeController's section splitter and a tiny in-class JSON parser.
 * Places gauges using center-based (x,y) with width (w) and height (h).
 */
public final class DashboardWindow implements WindowManager.WindowSceneController {

    // Root + layers
    private final StackPane root = new StackPane();
    private final Pane contentLayer = new Pane();
    private final StackPane overlayLayer = new StackPane();

    // Stage/shell
    private Stage stage;
    private WindowManager.ShellFacade shell;

    // Mode listener
    private ModeController.Listener modeListener;

    public DashboardWindow() {
        // no-op
    }

    // ---------------- WindowSceneController lifecycle ----------------

    @Override
    public Parent createContent(String initialModeKey) {
        // Layer order: content below overlay
        root.getChildren().setAll(contentLayer, overlayLayer);
        StackPane.setAlignment(contentLayer, Pos.TOP_LEFT);
        StackPane.setAlignment(overlayLayer, Pos.TOP_LEFT);

        // First paint if data already present
        rebuildFromModeJson(null);
        return root;
    }

    @Override
    public void onMounted(Stage stage, WindowManager.ShellFacade shell) {
        this.stage = stage;
        this.shell = shell;

        // Listen for future mode changes (FX thread-safe)
        this.modeListener = (name, rawJson) -> Platform.runLater(() -> rebuildFromModeJson(rawJson));
        ModeController.global().addListener(this.modeListener);
    }

    @Override
    public void onBeforeReveal() {
        // Try to render with whatever is currently loaded so first paint isn't empty
        rebuildFromModeJson(null);
    }

    @Override
    public void onAfterReveal() {
        // no-op (fade-ins etc. could go here)
    }

    @Override
    public void onClose() {
        // Clean up listener to avoid leaks
        if (this.modeListener != null) {
            ModeController.global().removeListener(this.modeListener);
            this.modeListener = null;
        }
    }

    // Optional: exposed so WindowManager can mount overlays if desired (via reflection)
    public StackPane getOverlaySlot() { return overlayLayer; }

    // ---------------- Rendering logic ----------------

    private void rebuildFromModeJson(String ignoredRaw) {
        // Pull the isolated dashboard section from ModeController
        String ds = ModeController.global().getDashboardSectionRaw();
        if (ds == null || ds.isEmpty()) {
            contentLayer.getChildren().clear();
            return;
        }

        // Deep diagnostics (safe)
        debugDumpJson("[Dashboard][Debug] dashboardSection", ds);

        // --- Normalize common gotchas so the parser survives ---
        String norm = (ModeController.global().getDashboardSectionRaw() == null ? "" : ModeController.global().getDashboardSectionRaw())
                .replace("\r", "")
                // strip standalone lines with just "..."
                .replaceAll("(?m)^\\s*\\.\\.\\.\\s*$", "")
                // replace curly “smart quotes” with normal quotes
                .replace('“','"').replace('”','"').replace('’','\'');

        // Parse using MiniJson (robust), then fall back to legacy scanner if needed
        List<DisplaySpec> specs = parseDashboardDisplaysViaJson(norm);
        if (specs.isEmpty()) {
            System.out.println("[Dashboard][Debug] JSON parser found 0 items. Trying legacy fallback.");
            specs = parseDashboardDisplays_Fallback(norm);
        }

        contentLayer.getChildren().clear();
        for (DisplaySpec spec : specs) {
            Node node = instantiate(spec.type);
            if (node == null) {
                log("[Dashboard] Could not instantiate: " + spec.type);
                continue;
            }

            // Load config JSON and call setConfig if exists
            Map<String, Object> cfg = readConfigJson(spec.configPath);
            trySetConfig(node, cfg);

            // Probe config resource presence (classpath check, dev help)
            probeResource(spec.configPath);

            // Center-based placement: x,y are the center; w,h are size
            trySetPrefSize(node, spec.w, spec.h); // ensure size before positioning
            double w = (spec.w > 0 ? spec.w : preferredW(node));
            double h = (spec.h > 0 ? spec.h : preferredH(node));
            node.relocate(spec.x - (w * 0.5), spec.y - (h * 0.5));

            contentLayer.getChildren().add(node);

            log("[Dashboard] mounted: type=" + spec.type +
                " config=" + spec.configPath + " bind=" + spec.bindKey +
                " center=(" + spec.x + "," + spec.y + ") size=(" + w + "," + h + ")");
        }
    }

    // ---------------- DisplaySpec model & parsers ----------------

    /** One gauge entry from dashboard.displays[]. */
    private static final class DisplaySpec {
        String type;
        String configPath;
        String bindKey;
        double x;
        double y;
        double w;
        double h;
    }

    private static String asString(Object o, String def) {
        return (o instanceof String s) ? s : def;
    }
    private static double asDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s.trim()); } catch (Exception ignore) {}
        return def;
    }

    /** Parse dashboard.displays[] using the built-in MiniJson parser. */
    @SuppressWarnings("unchecked")
    private List<DisplaySpec> parseDashboardDisplaysViaJson(String dashboardSectionRaw) {
        ArrayList<DisplaySpec> out = new ArrayList<>();
        if (dashboardSectionRaw == null || dashboardSectionRaw.isBlank()) return out;

        Object root = MiniJson.parse(dashboardSectionRaw);
        if (!(root instanceof Map<?,?> map)) return out;

        Object displays = map.get("displays");
        if (!(displays instanceof List<?> list)) return out;

        for (Object item : list) {
            if (!(item instanceof Map<?,?> m)) continue;
            DisplaySpec spec = new DisplaySpec();
            spec.type       = asString(m.get("type"), null);
            spec.configPath = asString(m.get("config"), null);
            spec.bindKey    = asString(m.get("bind"), null);
            spec.x          = asDouble(m.get("x"), 0);
            spec.y          = asDouble(m.get("y"), 0);
            spec.w          = asDouble(m.get("w"), 0);
            spec.h          = asDouble(m.get("h"), 0);

            if (spec.type == null || spec.type.isBlank()) {
                System.out.println("[Dashboard] Skipping display with missing 'type'");
                continue;
            }
            out.add(spec);
        }
        return out;
    }

    /**
     * Legacy fallback parser — brace-depth slice of displays[] with simple field extraction.
     * Keeps your existing resilience if someone drops a comma or has odd spacing.
     */
    private List<DisplaySpec> parseDashboardDisplays_Fallback(String norm) {
        ArrayList<DisplaySpec> out = new ArrayList<>();
        if (norm == null || norm.isBlank()) return out;

        // Find "displays" array
        String needle = "\"displays\"";
        int di = norm.indexOf(needle);
        if (di < 0) return out;
        int colon = norm.indexOf(':', di + needle.length());
        if (colon < 0) return out;

        // Find '[' start
        int arrStart = -1;
        for (int j = colon + 1; j < norm.length(); j++) {
            char c = norm.charAt(j);
            if (Character.isWhitespace(c)) continue;
            if (c == '[') { arrStart = j; break; }
            else return out;
        }
        if (arrStart < 0) return out;

        // Walk to matching ']' with depth tracking for nested objects
        int depth = 0, arrEnd = -1;
        for (int j = arrStart; j < norm.length(); j++) {
            char c = norm.charAt(j);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { arrEnd = j; break; }
            }
        }
        if (arrEnd < 0) return out;

        String arrayBody = norm.substring(arrStart + 1, arrEnd);
        // Split top-level objects { ... } by brace-depth
        int i = 0;
        while (i < arrayBody.length()) {
            // find next '{'
            int objStart = arrayBody.indexOf('{', i);
            if (objStart < 0) break;
            int d = 0, objEnd = -1;
            for (int j = objStart; j < arrayBody.length(); j++) {
                char c = arrayBody.charAt(j);
                if (c == '{') d++;
                else if (c == '}') {
                    d--;
                    if (d == 0) { objEnd = j; break; }
                }
            }
            if (objEnd < 0) break;

            String obj = arrayBody.substring(objStart, objEnd + 1);
            DisplaySpec spec = new DisplaySpec();
            spec.type       = extractString(obj, "\"type\"");
            spec.configPath = extractString(obj, "\"config\"");
            spec.bindKey    = extractString(obj, "\"bind\"");
            spec.x          = extractNumber(obj, "\"x\"");
            spec.y          = extractNumber(obj, "\"y\"");
            spec.w          = extractNumber(obj, "\"w\"");
            spec.h          = extractNumber(obj, "\"h\"");

            if (spec.type != null && !spec.type.isBlank()) out.add(spec);

            i = objEnd + 1;
        }
        return out;
    }

    // Tiny helpers for the fallback string parser
    private static String extractString(String src, String key) {
        int k = src.indexOf(key);
        if (k < 0) return null;
        int colon = src.indexOf(':', k + key.length());
        if (colon < 0) return null;
        int firstQuote = src.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int secondQuote = src.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        return src.substring(firstQuote + 1, secondQuote);
    }
    private static double extractNumber(String src, String key) {
        int k = src.indexOf(key);
        if (k < 0) return 0;
        int colon = src.indexOf(':', k + key.length());
        if (colon < 0) return 0;
        int j = colon + 1;
        while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
        int end = j;
        while (end < src.length()) {
            char c = src.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') { end++; continue; }
            break;
        }
        try {
            return Double.parseDouble(src.substring(j, end));
        } catch (Exception ignore) { return 0; }
    }

    // ---------------- Gauge instantiation / config / layout helpers ----------------

    private Node instantiate(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return null;
        try {
            Class<?> cls = Class.forName(fqcn);
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object obj = ctor.newInstance();
            if (obj instanceof Node n) return n;
            return null;
        } catch (Throwable t) {
            System.out.println("[Dashboard] instantiate failed for " + fqcn + ": " + t);
            return null;
        }
    }

    private void trySetPrefSize(Node node, double w, double h) {
        if (!(node instanceof Region r)) return;
        if (w > 0) r.setPrefWidth(w);
        if (h > 0) r.setPrefHeight(h);
    }

    private double preferredW(Node node) {
        return (node instanceof Region r) ? r.prefWidth(-1) : 0;
    }
    private double preferredH(Node node) {
        return (node instanceof Region r) ? r.prefHeight(-1) : 0;
    }

    // --- Helpers: config loading + reflective setConfig (no external JSON helper) ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfigJson(String resourcePath) {
        try {
            if (resourcePath == null || resourcePath.isBlank()) return java.util.Collections.emptyMap();
            String norm = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            try (java.io.InputStream in = Thread.currentThread()
                    .getContextClassLoader().getResourceAsStream(norm)) {
                if (in == null) {
                    System.out.println("[Dashboard] config not found on classpath: " + norm);
                    return java.util.Collections.emptyMap();
                }
                String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\r", "")
                        .replaceAll("(?m)^\\s*\\.\\.\\.\\s*$", "")
                        .replace('“','"').replace('”','"').replace('’','\'');

                Object root = MiniJson.parse(json);
                if (root instanceof Map) {
                    return (Map<String, Object>) root;
                }
                System.out.println("[Dashboard] config root is not an object: " + norm);
                return java.util.Collections.emptyMap();
            }
        } catch (Throwable t) {
            System.out.println("[Dashboard] readConfigJson error: " + t);
            return java.util.Collections.emptyMap();
        }
    }

    /** If the node exposes setConfig(Map<String,Object>), invoke it reflectively. */
    private void trySetConfig(Node node, Map<String, Object> cfg) {
        if (node == null || cfg == null) return;
        try {
            Method m = node.getClass().getMethod("setConfig", Map.class);
            m.invoke(node, cfg);
        } catch (NoSuchMethodException nsme) {
            // Gauge doesn't support setConfig(Map) — ignore.
        } catch (Throwable t) {
            System.out.println("[Dashboard] setConfig failed for " + node.getClass().getName() + ": " + t);
        }
    }

    // ---------------- Diagnostics / utils ----------------

    private void probeResource(String path) {
        if (path == null || path.isBlank()) return;
        String norm = path.startsWith("/") ? path.substring(1) : path;
        URL url = Thread.currentThread().getContextClassLoader().getResource(norm);
        if (url == null) {
            System.out.println("[Dashboard][Probe] MISSING resource: " + norm);
        } else {
            System.out.println("[Dashboard][Probe] found: " + url);
        }
    }

    private void debugDumpJson(String tag, String text) {
        if (text == null) {
            System.out.println(tag + " is null");
            return;
        }
        int len = text.length();
        System.out.println(tag + " length=" + len);
        System.out.println(tag + " head=\n" + text.substring(0, Math.min(400, len)));
        if (len > 400) {
            System.out.println(tag + " tail=\n" + text.substring(Math.max(0, len - 200)));
        }
    }

    private static void log(String s) {
        System.out.println(s);
    }

    // ---------------- Minimal JSON parser ----------------

    /** Minimal JSON parser (objects, arrays, strings, numbers, booleans, null). */
    private static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) { this.s = s; this.i = 0; }

        static Object parse(String s) {
            return new MiniJson(s).parseValueTrimmed();
        }

        private Object parseValueTrimmed() {
            skipWs();
            Object v = parseValue();
            skipWs();
            return v;
        }

        private Object parseValue() {
            skipWs();
            if (eof()) throw err("Unexpected end");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || isDigit(c)) return parseNumber();
            throw err("Unexpected char: " + c);
        }

        @SuppressWarnings("unchecked")
        private Map<String,Object> parseObject() {
            expect('{');
            java.util.LinkedHashMap<String,Object> map = new java.util.LinkedHashMap<>();
            skipWs();
            if (peek('}')) { i++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            return map;
        }

        private java.util.List<Object> parseArray() {
            expect('[');
            java.util.ArrayList<Object> list = new java.util.ArrayList<>();
            skipWs();
            if (peek(']')) { i++; return list; }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (peek(']')) { i++; break; }
                expect(',');
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (eof()) throw err("Bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i+4 > s.length()) throw err("Bad \\u escape");
                            String hex = s.substring(i, i+4);
                            i += 4;
                            sb.append((char)Integer.parseInt(hex, 16));
                            break;
                        default: throw err("Illegal escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (matchAhead("true"))  { i += 4; return Boolean.TRUE; }
            if (matchAhead("false")) { i += 5; return Boolean.FALSE; }
            throw err("Bad boolean");
        }

        private Object parseNull() {
            if (matchAhead("null")) { i += 4; return null; }
            throw err("Bad null");
        }

        private Number parseNumber() {
            int start = i;
            if (peek('-')) i++;
            while (!eof() && isDigit(s.charAt(i))) i++;
            if (peek('.')) {
                i++;
                while (!eof() && isDigit(s.charAt(i))) i++;
            }
            if (peek('e') || peek('E')) {
                i++;
                if (peek('+') || peek('-')) i++;
                while (!eof() && isDigit(s.charAt(i))) i++;
            }
            String m = s.substring(start, i);
            try {
                if (m.indexOf('.') >= 0 || m.indexOf('e') >= 0 || m.indexOf('E') >= 0) return Double.parseDouble(m);
                long lv = Long.parseLong(m);
                if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
                return lv;
            } catch (NumberFormatException nfe) {
                throw err("Bad number: " + m);
            }
        }

        // --- utils ---
        private void skipWs() {
            while (!eof()) {
                char c = s.charAt(i);
                if (c==' ' || c=='\t' || c=='\n' || c=='\r') i++;
                else break;
            }
        }
        private void expect(char ch) {
            if (eof() || s.charAt(i) != ch) throw err("Expected '" + ch + "'");
            i++;
        }
        private boolean peek(char ch) { return !eof() && s.charAt(i) == ch; }
        private boolean eof() { return i >= s.length(); }
        private boolean isDigit(char c) { return c >= '0' && c <= '9'; }
        private boolean matchAhead(String lit) {
            return s.regionMatches(i, lit, 0, lit.length());
        }
        private IllegalArgumentException err(String msg) {
            int from = Math.max(0, i-20), to = Math.min(s.length(), i+20);
            return new IllegalArgumentException(msg + " at " + i + " near: " + s.substring(from, to));
        }
    }
}


