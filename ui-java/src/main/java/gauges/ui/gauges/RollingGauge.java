package gauges.ui.gauges;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * RollingGauge — minimal scrolling line graph for quick pipeline tests.
 *
 * Config keys (all optional, safe defaults provided):
 *   range.min / range.max          : number (value clamp & vertical scale)
 *   display.history                : number (samples kept, e.g., 240)
 *   layout.width / layout.height   : number (initial preferred size)
 *   styles.bg / styles.grid / styles.line : hex colors
 *   simulate.enabled               : boolean
 *   simulate.min / .max            : number (sim range)
 *   simulate.speed_hz              : number (cycles per second)
 */
public class RollingGauge extends Region {

    private final Canvas canvas = new Canvas(420, 160);

    // Range & look
    private double min = 0, max = 100;
    private int history = 240;
    private Color bg   = Color.web("#0B0D12");
    private Color grid = Color.web("#223");
    private Color line = Color.web("#00B2FF");

    // Data
    private final Deque<Double> samples = new ArrayDeque<>();
    private double currentValue = 0;

    // Simulation
    private boolean simEnabled = false;
    private double simMin = 0, simMax = 100, simHz = 1.0;
    private long lastNs = 0;
    private double phase = 0;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (lastNs == 0) { lastNs = now; return; }
            double dt = (now - lastNs) / 1_000_000_000.0;
            lastNs = now;

            if (simEnabled) {
                phase += dt * simHz * Math.PI * 2.0; // radians/sec
                double mid = (simMin + simMax) * 0.5;
                double amp = Math.max(0, (simMax - simMin)) * 0.5;
                currentValue = mid + Math.sin(phase) * amp;
            }

            pushSample(currentValue);
            draw();
        }
    };

    public RollingGauge() {
        getChildren().add(canvas);

        // Redraw on size changes
        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());

        setPrefSize(canvas.getWidth(), canvas.getHeight());
        timer.start(); // keep the rolling effect alive
        draw();
    }

    /** Apply mode/config map (nested keys via dot-paths). */
    public void setConfig(Map<String, Object> cfg) {
        if (cfg == null) return;

        min     = d(cfg, "range.min", min);
        max     = d(cfg, "range.max", max);
        history = (int) Math.max(30, d(cfg, "display.history", history));

        bg   = color(s(cfg, "styles.bg",   "#0B0D12"));
        grid = color(s(cfg, "styles.grid", "#223"));
        line = color(s(cfg, "styles.line", "#00B2FF"));

        double w = d(cfg, "layout.width",  canvas.getWidth());
        double h = d(cfg, "layout.height", canvas.getHeight());
        canvas.setWidth(w);
        canvas.setHeight(h);
        setPrefSize(w, h);

        simEnabled = b(cfg, "simulate.enabled", false);
        simMin     = d(cfg, "simulate.min", min);
        simMax     = d(cfg, "simulate.max", max);
        simHz      = d(cfg, "simulate.speed_hz", 1.0);
        phase = 0;
        lastNs = 0;

        samples.clear();
        draw();
    }

    /** Inject a new live value (clamped). */
    public void setValue(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        double cv = Math.max(min, Math.min(max, v));
        currentValue = cv;
    }

    // --- Layout plumbing

    private void resizeCanvas() {
        double W = getWidth();
        double H = getHeight();
        if (W > 0 && H > 0) {
            canvas.setWidth(W);
            canvas.setHeight(H);
            draw();
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return canvas.getWidth();
    }

    @Override
    protected double computePrefHeight(double width) {
        return canvas.getHeight();
    }

    @Override
    protected void layoutChildren() {
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    // --- Drawing

    private void pushSample(double v) {
        samples.addLast(v);
        while (samples.size() > history) samples.removeFirst();
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth();
        double H = canvas.getHeight();

        // Background
        g.setFill(bg);
        g.fillRect(0, 0, W, H);

        // Grid (lightweight)
        g.setStroke(grid);
        g.setLineWidth(1);
        int vlines = 8, hlines = 4;
        for (int i = 1; i < vlines; i++) {
            double x = W * i / vlines;
            g.strokeLine(x, 0, x, H);
        }
        for (int j = 1; j < hlines; j++) {
            double y = H * j / hlines;
            g.strokeLine(0, y, W, y);
        }

        // Nothing to draw yet
        if (samples.isEmpty()) return;

        // Polyline from samples
        double prevX = 0, prevY = map(samples.peekFirst(), min, max, H, 0);
        int idx = 0, n = samples.size();

        g.setStroke(line);
        g.setLineWidth(Math.max(2, H * 0.02));

        for (double sv : samples) {
            double x = W * idx / Math.max(1, n - 1);
            double y = map(sv, min, max, H, 0);
            if (idx > 0) g.strokeLine(prevX, prevY, x, y);
            prevX = x; prevY = y; idx++;
        }
    }

    private static double map(double v, double inMin, double inMax, double outMin, double outMax) {
        double t = (v - inMin) / Math.max(1e-9, (inMax - inMin));
        t = Math.max(0, Math.min(1, t));
        return outMin + t * (outMax - outMin);
    }

    // --- Small config helpers

    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> m, String path) {
        String[] parts = path.split("\\.");
        Object cur = m;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    private static double d(Map<String, Object> m, String p, double def) {
        Object o = get(m, p);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignore) {}
        }
        return def;
    }

    private static String s(Map<String, Object> m, String p, String def) {
        Object o = get(m, p);
        return (o == null) ? def : Objects.toString(o);
    }

    private static boolean b(Map<String, Object> m, String p, boolean def) {
        Object o = get(m, p);
        if (o instanceof Boolean B) return B;
        if (o instanceof String S) return Boolean.parseBoolean(S);
        return def;
    }

    private static Color color(String hex) {
        try { return Color.web(hex); } catch (Exception e) { return Color.WHITE; }
    }
}


