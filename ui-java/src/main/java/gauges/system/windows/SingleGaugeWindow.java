package gauges.system.windows;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
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
 * SingleGaugeWindow
 * Renders the one gauge defined in the single_gauge section of the current mode.
 * Expected primary schema (preferred):
 *   {
 *     "type":   "gauges.ui.gauges.CenterTextGauge",
 *     "config": "configs/gauges/center_style_a.json",
 *     "bind":   "current.value"
 *   }
 *
 * Also supports legacy array form:
 *   { "displays": [ { type, config, bind } ] }
 *
 * The gauge is sized to fill the window (prefSize bound to content size).
 */
public final class SingleGaugeWindow implements WindowManager.WindowSceneController {

    // Root + layers
    private final StackPane root = new StackPane();
    private final Pane contentLayer = new Pane();
    private final StackPane overlayLayer = new StackPane();

    // Stage/shell
    private Stage stage;
    private WindowManager.ShellFacade shell;

    // Mode listener
    private ModeController.Listener modeListener;

    // The mounted gauge node (we keep it so we can reapply size on window changes if needed)
    private Node mountedGauge;

    public SingleGaugeWindow() {
        // no-op
    }

    // ---------------- WindowSceneController lifecycle ----------------

    @Override
    public Parent createContent(String initialModeKey) {
        root.getChildren().setAll(contentLayer, overlayLayer);
        StackPane.setAlignment(contentLayer, Pos.TOP_LEFT);
        StackPane.setAlignment(overlayLayer, Pos.TOP_LEFT);

        // When root resizes, keep mounted gauge filling the window
        root.widthProperty().addListener((o, a, b) -> fitGaugeToWindow());
        root.heightProperty().addListener((o, a, b) -> fitGaugeToWindow());

        rebuildFromModeJson(null);
        return root;
    }

    @Override
    public void onMounted(Stage stage, WindowManager.ShellFacade shell) {
        this.stage = stage;
        this.shell = shell;
        this.modeListener = (name, rawJson) -> Platform.runLater(() -> rebuildFromModeJson(rawJson));
        ModeController.global().addListener(this.modeListener);
    }

    @Override
    public void onBeforeReveal() {
        rebuildFromModeJson(null);
    }

    @Override
    public void onAfterReveal() {
        // no-op
    }

    @Override
    public void onClose() {
        if (this.modeListener != null) {
            ModeController.global().removeListener(this.modeListener);
            this.modeListener = null;
        }
    }

    // Optional: allow WindowManager to mount overlays
    public StackPane getOverlaySlot() { return overlayLayer; }

    // ---------------- Rendering logic ----------------

    private void rebuildFromModeJson(String ignoredRaw) {
        String sg = ModeController.global().getSingleGaugeSectionRaw();
        if (sg == null || sg.isEmpty()) {
            contentLayer.getChildren().clear();
            mountedGauge = null;
            return;
        }

        // Dev diagnostics (safe)
        debugDumpJson("[SingleGauge][Debug] singleGaugeSection", sg);

        // Normalize for parser resilience
        String norm = sg.replace("\r", "")
                        .replaceAll("(?m)^\\s*\\.\\.\\.\\s*$", "")
                        .replace('“','"').replace('”','"').replace('’','\'');

        // Parse a single spec; support both primary (flat object) and legacy (displays[0]) schemas
        DisplaySpec spec = parseSingleSpec(norm);
        if (spec == null || spec.type == null || spec.type.isBlank()) {
            System.out.println("[SingleGauge] No valid spec found.");
            contentLayer.getChildren().clear();
            mountedGauge = null;
            return;
        }

        // Instantiate gauge
        Node node = instantiate(spec.type);
        if (node == null) {
            System.out.println("[SingleGauge] Could not instantiate type: " + spec.type);
            contentLayer.getChildren().clear();
            mountedGauge = null;
            return;
        }

        // Load config and apply setConfig(Map) if available
        Map<String,Object> cfg = readConfigJson(spec.configPath);
        trySetConfig(node, cfg);

        // Probe config resource presence for dev feedback
        probeResource(spec.configPath);

        // Mount and size to window
        contentLayer.getChildren().setAll(node);
        mountedGauge = node;
        fitGaugeToWindow();

        System.out.println("[SingleGauge] mounted: type=" + spec.type +
                           " config=" + spec.configPath + " bind=" + spec.bindKey);
    }

    /** Ensure the mounted gauge fills the window area (0,0 to root size). */
    private void fitGaugeToWindow() {
        if (mountedGauge == null) return;
        double w = Math.max(0, root.getWidth());
        double h = Math.max(0, root.getHeight());

        // Place at (0,0)
        mountedGauge.relocate(0, 0);

        // If Region, prefer pref sizing; otherwise fallback to resize if available
        if (mountedGauge instanceof Region r) {
            if (w > 0) r.setPrefWidth(w);
            if (h > 0) r.setPrefHeight(h);
            // Some custom Regions might also respect max/min; we can set them generously
            r.setMinSize(0, 0);
            r.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        } else {
            try {
                Method resize = mountedGauge.getClass().getMethod("resize", double.class, double.class);
                resize.invoke(mountedGauge, w, h);
            } catch (NoSuchMethodException ignore) {
                // Many Nodes don't support resize; they will typically draw to their canvas size or pref size.
            } catch (Throwable t) {
                System.out.println("[SingleGauge] resize() failed: " + t);
            }
        }
    }

    // ---------------- Spec & parsing ----------------

    private static final class DisplaySpec {
        String type;
        String configPath;
        String bindKey;
    }

    @SuppressWarnings("unchecked")
    private DisplaySpec parseSingleSpec(String raw) {
        Object root = MiniJson.parse(raw);
        if (root instanceof Map<?,?> map) {
            // Preferred schema: flat object with type/config/bind
            String type   = asString(map.get("type"), null);
            String config = asString(map.get("config"), null);
            String bind   = asString(map.get("bind"), null);

            if (type != null) {
                DisplaySpec s = new DisplaySpec();
                s.type = type; s.configPath = config; s.bindKey = bind;
                return s;
            }

            // Legacy schema: { "displays": [ {..first..} ] }
            Object displays = map.get("displays");
            if (displays instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?,?> m0) {
                DisplaySpec s = new DisplaySpec();
                s.type       = asString(m0.get("type"), null);
                s.configPath = asString(m0.get("config"), null);
                s.bindKey    = asString(m0.get("bind"), null);
                if (s.type != null) return s;
            }
        }
        return null;
    }

    private static String asString(Object o, String def) {
        return (o instanceof String s) ? s : def;
    }

    // ---------------- Gauge instantiation / config helpers ----------------

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
            System.out.println("[SingleGauge] instantiate failed for " + fqcn + ": " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfigJson(String resourcePath) {
        try {
            if (resourcePath == null || resourcePath.isBlank()) return java.util.Collections.emptyMap();
            String norm = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            try (java.io.InputStream in = Thread.currentThread()
                    .getContextClassLoader().getResourceAsStream(norm)) {
                if (in == null) {
                    System.out.println("[SingleGauge] config not found on classpath: " + norm);
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
                System.out.println("[SingleGauge] config root is not an object: " + norm);
                return java.util.Collections.emptyMap();
            }
        } catch (Throwable t) {
            System.out.println("[SingleGauge] readConfigJson error: " + t);
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
            System.out.println("[SingleGauge] setConfig failed for " + node.getClass().getName() + ": " + t);
        }
    }

    // ---------------- Diagnostics / utils ----------------

    private void probeResource(String path) {
        if (path == null || path.isBlank()) return;
        String norm = path.startsWith("/") ? path.substring(1) : path;
        URL url = Thread.currentThread().getContextClassLoader().getResource(norm);
        if (url == null) {
            System.out.println("[SingleGauge][Probe] MISSING resource: " + norm);
        } else {
            System.out.println("[SingleGauge][Probe] found: " + url);
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


