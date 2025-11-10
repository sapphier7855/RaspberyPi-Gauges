package gauges.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ModeController
 *
 * Responsibilities:
 *  - Provide a global singleton (ModeController.global()).
 *  - Load mode JSON files from the classpath (under configs/Modes/ or configs/modes/).
 *  - Cache the raw JSON and expose it to windows that will parse & render displays.
 *  - Notify listeners when a mode is loaded/changed.
 *
 * Conventions:
 *  - Classpath paths MUST NOT include "resources/". Place files under:
 *        src/main/resources/configs/Modes/mode1.json
 *        src/main/resources/configs/Modes/mode2.json
 *    or use lower-case "modes" if you prefer (this loader checks both).
 *
 * Typical use:
 *    ModeController mc = ModeController.global();
 *    mc.addListener(name -> { /* rebuild windows from mc.getRawJson() */ /* });
 *    mc.load("mode1");
 *
 * Windows can then parse mc.getRawJson() to pull:
 *    - dashboard.displays[]
 *    - single_gauge.displays[]
 */
public final class ModeController {

    // --------- Singleton ---------
    private static final ModeController INSTANCE = new ModeController();
    public static ModeController global() { return INSTANCE; }

    // --------- Listener API ---------
    /** Simple listener invoked after a mode has been loaded and cached. */
    public interface Listener {
        void onModeChanged(String modeName, String rawJson);
    }
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // --------- Aliases (optional) ---------
    /**
     * If you want "mode1" to map to a different filename (e.g., "mode1_general"),
     * you can set an alias here. By default, we map mode1 -> mode1 and mode2 -> mode2.
     */
    private final Map<String, String> aliases = new LinkedHashMap<>();
    {
        // Default identity aliases; customize if needed:
        aliases.put("mode1", "mode1");
        aliases.put("mode2", "mode2");
    }

    /** Override or add an alias (e.g., setAlias("mode1", "mode1_general")). */
    public void setAlias(String modeName, String fileStem) {
        Objects.requireNonNull(modeName, "modeName");
        Objects.requireNonNull(fileStem, "fileStem");
        aliases.put(modeName, fileStem);
    }

    // --------- State ---------
    private volatile String currentModeName = null;
    private volatile String currentRawJson = null;
    private volatile String currentDashboardSection = null;
    private volatile String currentSingleGaugeSection = null;


    public String getCurrentModeName() { return currentModeName; }
    public String getRawJson() { return currentRawJson; }

    public String getDashboardSectionRaw() { return currentDashboardSection; }
    public String getSingleGaugeSectionRaw() { return currentSingleGaugeSection; }

    // --------- Public API ---------
    /**
     * Load a mode by name (e.g., "mode1", "mode2").
     * This method resolves aliases, then attempts to load
     *   configs/Modes/<fileStem>.json
     *   configs/modes/<fileStem>.json
     * from the classpath.
     *
     * @throws IllegalStateException if the resource cannot be found/read.
     */
    public synchronized void load(String modeName) {
        Objects.requireNonNull(modeName, "modeName");
        final String fileStem = aliases.getOrDefault(modeName, modeName);

        // Generate candidate classpath paths (NO "resources/" prefix)
        final String[] candidates = new String[] {
            "configs/Modes/" + fileStem + ".json",  // as in your logs
            "configs/modes/" + fileStem + ".json"   // lowercase fallback
        };

        String json = null;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ModeController.class.getClassLoader();

        for (String rel : candidates) {
            try (InputStream in = cl.getResourceAsStream(rel)) {
                if (in != null) {
                    json = slurpUtf8(in);
                    if (json != null) {
                        debugFound(rel);
                        break;
                    }
                } else {
                    debugProbe(rel, null);
                }
            } catch (IOException io) {
                debugProbe(rel, io);
            }
        }

        if (json == null) {
            debugClasspathPresence(cl);
            final String msg = "[Mode] Failed to load '" + modeName + "': Resource not found. Tried: "
                    + String.join(", ", candidates)
                    + hints();
            throw new IllegalStateException(msg);
        }

        this.currentModeName = modeName;
        this.currentRawJson  = json;
        this.currentDashboardSection = sliceTopLevelObject(json, "dashboard");
        this.currentSingleGaugeSection = sliceTopLevelObject(json, "single_gauge");


        // Notify listeners
        for (Listener l : listeners) {
            try {
                l.onModeChanged(modeName, json);
            } catch (Throwable t) {
                System.out.println("[Mode] Listener threw: " + t);
            }
        }
    }

    /**
     * Returns a list of discoverable mode file names (without .json) under
     * configs/Modes and configs/modes. Handy for exposing a mode-picker UI.
     * Note: In an executable JAR, listing may be limited. We'll try our best.
     */
    public List<String> listAvailableModes() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ModeController.class.getClassLoader();
        List<String> out = new ArrayList<>();
        out.addAll(listModesUnder(cl, "configs/Modes"));
        out.addAll(listModesUnder(cl, "configs/modes"));
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    // --------- Helpers ---------
    private static String slurpUtf8(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static void debugFound(String rel) {
        System.out.println("[Mode] Loaded: " + rel);
    }

    private static void debugProbe(String rel, Exception e) {
        if (e == null) {
            System.out.println("[Mode][Probe] Not found on classpath: " + rel);
        } else {
            System.out.println("[Mode][Probe] Error reading " + rel + ": " + e);
        }
    }

    private static void debugClasspathPresence(ClassLoader cl) {
        try {
            System.out.println("[Mode][Debug] Listing classpath roots for 'configs':");
            Enumeration<URL> roots = cl.getResources("configs");
            while (roots.hasMoreElements()) {
                URL u = roots.nextElement();
                System.out.println("  - " + u);
            }
            System.out.println("[Mode][Debug] Probing common casings:");
            System.out.println("    configs/Modes/ → " + cl.getResource("configs/Modes/"));
            System.out.println("    configs/modes/ → " + cl.getResource("configs/modes/"));
        } catch (Exception e) {
            System.out.println("[Mode][Debug] Error enumerating 'configs': " + e);
        }
        // Also check dev-time filesystem
        try {
            java.nio.file.Path p1 = java.nio.file.Paths.get("src/main/resources/configs/Modes");
            java.nio.file.Path p2 = java.nio.file.Paths.get("src/main/resources/configs/modes");
            System.out.println("[Mode][Debug] Dev FS exists: " + p1 + "=" + java.nio.file.Files.exists(p1)
                    + ", " + p2 + "=" + java.nio.file.Files.exists(p2));
        } catch (Exception ignore) {}
    }

    private static List<String> listModesUnder(ClassLoader cl, String base) {
        try {
            URL baseUrl = cl.getResource(base);
            if (baseUrl == null) return Collections.emptyList();

            String protocol = baseUrl.getProtocol();
            if ("file".equalsIgnoreCase(protocol)) {
                // Running from classes directory
                java.nio.file.Path dir = java.nio.file.Paths.get(baseUrl.toURI());
                if (!java.nio.file.Files.isDirectory(dir)) return Collections.emptyList();
                List<String> out = new ArrayList<>();
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".json"))
                     .forEach(p -> {
                         String name = p.getFileName().toString();
                         out.add(name.substring(0, name.length() - ".json".length()));
                     });
                }
                return out;
            } else if ("jar".equalsIgnoreCase(protocol)) {
                // Running from a jar; listing is trickier. Try a best-effort via the JAR file.
                String spec = baseUrl.toString();
                // Example: jar:file:/path/app.jar!/configs/Modes
                int bang = spec.indexOf("!");
                if (bang > 0) {
                    String jarPath = spec.substring("jar:".length(), bang);
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new java.io.File(new java.net.URI(jarPath)))) {
                        List<String> out = new ArrayList<>();
                        String prefix = base + "/";
                        java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries();
                        while (e.hasMoreElements()) {
                            java.util.jar.JarEntry je = e.nextElement();
                            String name = je.getName();
                            if (name.startsWith(prefix) && !je.isDirectory() && name.endsWith(".json")) {
                                String stem = name.substring(prefix.length(), name.length() - ".json".length());
                                // Only include top-level files, not nested paths
                                if (!stem.contains("/")) out.add(stem);
                            }
                        }
                        return out;
                    } catch (Exception ignore) {
                        // Fall through
                    }
                }
            }
        } catch (Exception ignore) {
            // Fall through
        }
        return Collections.emptyList();
    }

        /** Extract top-level object section like { ... } for a given key. Returns null if not found. */
    private static String sliceTopLevelObject(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\""; // "dashboard"
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        // Find first '{' after colon
        int brace = -1;
        for (int j = colon+1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (Character.isWhitespace(c)) continue;
            if (c == '{') { brace = j; break; }
            else return null; // not an object
        }
        if (brace < 0) return null;
        int depth = 0;
        for (int j = brace; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(brace, j+1);
                }
            }
        }
        return null;
    }

private static String hints() {
        return "\n[Hints]\n" +
               "  • Place files under: src/main/resources/configs/Modes/<name>.json (or lowercase 'modes').\n" +
               "  • Do NOT prefix 'resources/' in getResourceAsStream paths.\n" +
               "  • On Linux/macOS, 'Modes' ≠ 'modes' (case-sensitive).\n" +
               "  • If running from a JAR, ensure Gradle packaged resources (processResources).\n";
    }
}

