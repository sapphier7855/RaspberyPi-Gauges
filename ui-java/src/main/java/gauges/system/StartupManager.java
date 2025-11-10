package gauges.system;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * StartupManager — unified boot controller (overlay + readiness).
 *
 * - Shows your splash immediately (mount before Stage.show()).
 * - Tracks startup "phases" (windows ready, pipeline ready, mode applied, etc.).
 * - Updates splash progress as phases complete.
 * - Fades splash ONLY when ALL phases are ready (no timeouts / no partial dismiss).
 *
 * Keep your existing splash views (MainSplashView / GaugeSplashView / ControlSplashView).
 * Use SplashBridge to connect progress/messages into those views.
 */
public final class StartupManager {

    // -------------------------------------------------------------------------
    // Public API types
    // -------------------------------------------------------------------------

    /** Wire these callbacks to your splash view's setters. */
    public interface SplashBridge {
        /** Called right after the splash is mounted. */
        default void onShow() {}
        /** Called for progress updates in [0..1]. */
        default void onProgress(double pct) {}
        /** Optional text helpers. */
        default void setPrimary(String text) {}
        default void setSecondary(String text) {}
        /** Called just before fade-out begins. */
        default void onReady() {}
    }

    /** A startup phase; ready when it returns true or when its future completes. */
    public interface Phase {
        boolean isReady();
        String name();

        // ---- Factories ----

        /** Poll a BooleanSupplier until it returns true. */
        static Phase flag(String name, BooleanSupplier supplier) {
            Objects.requireNonNull(supplier, "supplier");
            return new Phase() {
                @Override public boolean isReady() { return supplier.getAsBoolean(); }
                @Override public String name() { return name; }
            };
        }

        /** Ready when the given future is done (success OR failure). */
        static Phase future(String name, CompletableFuture<?> future) {
            Objects.requireNonNull(future, "future");
            return new Phase() {
                @Override public boolean isReady() { return future.isDone(); }
                @Override public String name() { return name; }
            };
        }

        /** Manual phase: call markReady() when done. */
        static Manual manual(String name) {
            return new Manual(name);
        }

        /** Handle for a manual phase. */
        final class Manual implements Phase {
            private final String name;
            private volatile boolean ready = false;
            private Manual(String name) { this.name = name; }
            public void markReady() { ready = true; }
            @Override public boolean isReady() { return ready; }
            @Override public String name() { return name; }
        }
    }

    /** Configuration for mounting/unmounting and visuals. */
    public static final class Config {
        /** Add the splash node into your overlay (e.g., WindowManager.getDashboardOverlaySlot().getChildren()::add). */
        public Consumer<Node> mountOverlay = n -> {};
        /** Remove the splash node from your overlay. */
        public Consumer<Node> unmountOverlay = n -> {};
        /** Optional extra progress callback (e.g., for logging UI). */
        public BiConsumer<Double, String> onProgress = (p, lbl) -> {};

        /** Poll period for checking phases (milliseconds). */
        public long pollMillis = 60L;
        /** Fade-out duration when dismissing the splash. */
        public Duration fadeOut = Duration.millis(750);

        /** If true, prints log lines to System.out. */
        public boolean verbose = false;
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Config cfg;
    private final Map<String, Phase> phases = new LinkedHashMap<>();
    private final Set<String> readySet = Collections.synchronizedSet(new LinkedHashSet<>());
    private final ScheduledThreadPoolExecutor poller = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "StartupManager-Poller");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean started = false;
    private volatile boolean dismissed = false;

    private Node splashNode;               // your splash Node
    private SplashBridge splashBridge;     // adapters to your splash setters

    public StartupManager(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        poller.setRemoveOnCancelPolicy(true);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Mount the splash immediately (call before Stage.show()).
     * @param splashView the Node returned by your MainSplashView/GaugeSplashView/etc.
     * @param bridge     callbacks wired to that view's API (title/subtitle/progress).
     */
    public void showSplash(Node splashView, SplashBridge bridge) {
        Objects.requireNonNull(splashView, "splashView");
        this.splashNode = splashView;
        this.splashBridge = (bridge == null ? new SplashBridge() {} : bridge);

        Platform.runLater(() -> {
            cfg.mountOverlay.accept(splashView);
            splashBridge.onShow();
            updateProgressUI(); // initialize (0%)
            log("[Startup] Splash shown");
        });
    }

    /** Register a phase (unique name). */
    public void registerPhase(Phase phase) {
        Objects.requireNonNull(phase, "phase");
        String key = phase.name();
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Phase name is blank");
        if (phases.containsKey(key)) throw new IllegalArgumentException("Duplicate phase: " + key);
        phases.put(key, phase);
    }

    /** Create & register a manual phase; call markReady() later. */
    public Phase.Manual registerManual(String name) {
        Phase.Manual m = Phase.manual(name);
        registerPhase(m);
        return m;
    }

    /** Begin polling phases and driving the splash progress. Call once. */
    public void start() {
        if (started) return;
        started = true;
        poller.scheduleAtFixedRate(this::tick, 0L, cfg.pollMillis, TimeUnit.MILLISECONDS);
        log("[Startup] Polling started (phases=" + phases.keySet() + ")");
    }

    /** Fade out and unmount the splash now. Normally invoked automatically when all phases are ready. */
    public void dismiss() {
        if (dismissed) return;
        dismissed = true;

        Platform.runLater(() -> {
            if (splashNode == null) return;
            try {
                splashBridge.onReady();
                FadeTransition ft = new FadeTransition(cfg.fadeOut, splashNode);
                ft.setFromValue(splashNode.getOpacity());
                ft.setToValue(0.0);
                ft.setOnFinished(e -> {
                    cfg.unmountOverlay.accept(splashNode);
                    splashNode = null;
                    log("[Startup] Splash dismissed");
                });
                ft.play();
            } catch (Throwable t) {
                // best effort fallback
                cfg.unmountOverlay.accept(splashNode);
                splashNode = null;
            }
        });

        poller.shutdownNow();
    }

    /** 0..1 ratio of ready phases. */
    public double progress() {
        int total = phases.size();
        if (total == 0) return 1.0;
        int ready = readySet.size();
        return Math.max(0d, Math.min(1d, (double) ready / (double) total));
    }

    /** True when every registered phase has signaled ready. */
    public boolean allReady() {
        return !phases.isEmpty() && readySet.size() == phases.size();
    }

    // -------------------------------------------------------------------------
    // Internal loop
    // -------------------------------------------------------------------------

    private void tick() {
        // 1) Check phases
        for (Map.Entry<String, Phase> e : phases.entrySet()) {
            String key = e.getKey();
            if (readySet.contains(key)) continue;
            boolean rdy = false;
            try { rdy = e.getValue().isReady(); } catch (Throwable ignored) {}
            if (rdy) {
                readySet.add(key);
                log("[Startup] Phase ready: " + key);
            }
        }

        // 2) Update splash progress
        updateProgressUI();

        // 3) Dismiss strictly when ALL phases are ready (no timeouts or partials)
        if (allReady()) {
            dismiss();
        }
    }

    private void updateProgressUI() {
        double pct = progress();
        Platform.runLater(() -> {
            if (splashBridge != null) {
                splashBridge.onProgress(pct);
            }
            cfg.onProgress.accept(pct, readySet.size() + " / " + phases.size());
        });
    }

    private void log(String s) { if (cfg.verbose) System.out.println(s); }
}


