package gauges.ui.gauges;

import java.util.Map;
import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * NoTextGaugeDigital — basic digital readout with a tiny progress bar.
 *
 * Expected config keys (all optional, with safe defaults):
 *   range.min / range.max : number
 *   display.format        : "0", "0.0", "0.00", ...
 *   display.suffix        : string (e.g., "°C", "RPM")
 *   layout.width / .height: number (initial size; resizes with Region)
 *   styles.bg / .fg / .accent : hex colors (e.g., "#00D1FF")
 *   simulate.enabled      : boolean
 *   simulate.min / .max   : number
 *   simulate.speed_hz     : number (cycles per second)
 */
public class NoTextGaugeDigital extends Region {

    private final Canvas canvas = new Canvas(300, 140);
    private final DoubleProperty value = new SimpleDoubleProperty(0);

    // --- Config (defaults)
    private double min = 0;
    private double max = 100;
    private String format = "0.0";
    private String suffix = "";
    private Color bg = Color.web("#101015");
    private Color fg = Color.web("#00D1FF");
    private Color accent = Color.web("#2A2E39");

    // --- Simulation
    private boolean simEnabled = false;
    private double simMin = 0, simMax = 100, simSpeedHz = 0.5;
    private double simPhase = 0;
    private long lastNs = 0;
    private final AnimationTimer simTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!simEnabled) return;
            if (lastNs == 0) { lastNs = now; return; }
            double dt = (now - lastNs) / 1_000_000_000.0;
            lastNs = now;

            simPhase += dt * simSpeedHz * Math.PI * 2.0; // full cycle per Hz
            double mid = (simMin + simMax) * 0.5;
            double amp = Math.max(0, (simMax - simMin)) * 0.5;
            setValue(mid + Math.sin(simPhase) * amp);
        }
    };

    public NoTextGaugeDigital() {
        getChildren().add(canvas);

        // Redraw on changes
        value.addListener((obs, ov, nv) -> draw());
        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());

        // Initial paint
        resizeCanvas();
        draw();
    }

    /** Apply a mode/config map (nested keys via dot path). */
    public void setConfig(Map<String, Object> cfg) {
        if (cfg == null) return;

        // Range
        min = readD(cfg, "range.min", min);
        max = readD(cfg, "range.max", max);

        // Display
        format = readS(cfg, "display.format", format);
        suffix = readS(cfg, "display.suffix", suffix);

        // Styles
        bg     = parseColor(readS(cfg, "styles.bg", "#101015"));
        fg     = parseColor(readS(cfg, "styles.fg", "#00D1FF"));
        accent = parseColor(readS(cfg, "styles.accent", "#2A2E39"));

        // Layout (initial preferred size only; Region will still resize)
        double w = readD(cfg, "layout.width", canvas.getWidth());
        double h = readD(cfg, "layout.height", canvas.getHeight());
        canvas.setWidth(w);
        canvas.setHeight(h);
        setPrefSize(w, h);

        // Simulation
        simEnabled = readB(cfg, "simulate.enabled", false);
        simMin     = readD(cfg, "simulate.min", min);
        simMax     = readD(cfg, "simulate.max", max);
        simSpeedHz = readD(cfg, "simulate.speed_hz", 0.5);
        simPhase = 0;
        lastNs = 0;
        if (simEnabled) simTimer.start(); else simTimer.stop();

        draw();
    }

    /** Update the displayed value (clamped to range). */
    public void setValue(double v) {
        double cv = Math.max(min, Math.min(max, v));
        value.set(cv);
    }

    public double getValue() { return value.get(); }
    public DoubleProperty valueProperty() { return value; }

    // --- Layout/Render plumbing

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
    protected double computePrefWidth(double height) { return canvas.getWidth(); }

    @Override
    protected double computePrefHeight(double width) { return canvas.getHeight(); }

    @Override
    protected void layoutChildren() {
        // Canvas fills this Region
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth();
        double H = canvas.getHeight();

        // Background
        g.setFill(bg);
        g.fillRect(0, 0, W, H);

        // Container
        double pad = Math.min(W, H) * 0.08;
        double rx = Math.min(W, H) * 0.12;
        g.setFill(accent);
        g.fillRoundRect(pad, pad, W - 2 * pad, H - 2 * pad, rx, rx);

        // Value text
        String txt = formatValue(value.get(), format) + suffix;

        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFill(fg);

        double fs = Math.min(W, H) * 0.42;
        if (txt.length() > 6) fs *= 0.85;
        if (txt.length() > 10) fs *= 0.70;
        g.setFont(Font.font(Math.max(10, fs)));
        g.fillText(txt, W * 0.5, H * 0.52);

        // Bottom progress bar
        double pct = (value.get() - min) / Math.max(1e-9, (max - min));
        pct = Math.max(0, Math.min(1, pct));

        double barW = (W - 2 * pad);
        double filledW = barW * pct;
        double barH = Math.max(4, H * 0.08);
        double by = H - pad - barH;
        double r = barH;

        g.setFill(fg.deriveColor(0, 1, 1, 0.25));
        g.fillRoundRect(pad, by, barW, barH, r, r);

        g.setFill(fg);
        g.fillRoundRect(pad, by, filledW, barH, r, r);
    }

    // --- Tiny config helpers

    @SuppressWarnings("unchecked")
    private static Object getPath(Map<String, Object> m, String path) {
        String[] parts = path.split("\\.");
        Object cur = m;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    private static double readD(Map<String, Object> m, String p, double def) {
        Object o = getPath(m, p);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignore) {}
        }
        return def;
    }

    private static String readS(Map<String, Object> m, String p, String def) {
        Object o = getPath(m, p);
        return (o == null) ? def : Objects.toString(o);
    }

    private static boolean readB(Map<String, Object> m, String p, boolean def) {
        Object o = getPath(m, p);
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static Color parseColor(String hex) {
        try { return Color.web(hex); } catch (Exception e) { return Color.WHITE; }
    }

    private static String formatValue(double v, String pattern) {
        // Simple formatter: use number of decimals implied by pattern ("0", "0.0", "0.00", ...)
        int dp = 0;
        int dot = pattern.indexOf('.');
        if (dot >= 0) dp = Math.max(0, pattern.length() - dot - 1);
        return String.format("%." + dp + "f", v);
    }
}

