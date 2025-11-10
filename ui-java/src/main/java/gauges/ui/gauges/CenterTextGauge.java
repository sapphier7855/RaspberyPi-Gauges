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
 * CenterTextGauge — circular digital/analog hybrid gauge.
 *
 * Config keys (all optional):
 *   range.min / range.max           : numeric
 *   display.title                   : string (center label)
 *   display.format / display.suffix : string
 *   layout.width / layout.height    : numeric
 *   styles.bg / styles.title / styles.value : hex color
 *   styles.ring.bg / styles.ring.fg : hex color
 *   simulate.enabled / simulate.min / simulate.max / simulate.speed_hz
 */
public class CenterTextGauge extends Region {

    private final Canvas canvas = new Canvas(320, 180);
    private final DoubleProperty value = new SimpleDoubleProperty(0);

    // Config defaults
    private double min = 0, max = 100;
    private String title = "CENTER";
    private String format = "0";
    private String suffix = "";
    private Color bg = Color.web("#0D0F14");
    private Color titleColor = Color.web("#9AA4B2");
    private Color valueColor = Color.web("#28E0A6");
    private Color ringBack = Color.web("#1B2230");
    private Color ringFore = Color.web("#28E0A6");

    // Simulation
    private boolean simEnabled = false;
    private double simMin = 0, simMax = 100, simHz = 0.5;
    private double phase = 0;
    private long lastNs = 0;
    private final AnimationTimer simTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!simEnabled) return;
            if (lastNs == 0) { lastNs = now; return; }
            double dt = (now - lastNs) / 1_000_000_000.0;
            lastNs = now;

            phase += dt * simHz * Math.PI * 2.0;
            double mid = (simMin + simMax) * 0.5;
            double amp = Math.max(0, (simMax - simMin)) * 0.5;
            setValue(mid + Math.sin(phase) * amp);
        }
    };

    public CenterTextGauge() {
        getChildren().add(canvas);
        value.addListener((obs, ov, nv) -> draw());
        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());
        setPrefSize(canvas.getWidth(), canvas.getHeight());
        draw();
    }

    // --- Configuration ---
    public void setConfig(Map<String, Object> cfg) {
        if (cfg == null) return;

        min = d(cfg, "range.min", min);
        max = d(cfg, "range.max", max);
        title = s(cfg, "display.title", title);
        format = s(cfg, "display.format", format);
        suffix = s(cfg, "display.suffix", suffix);

        bg = color(s(cfg, "styles.bg", "#0D0F14"));
        titleColor = color(s(cfg, "styles.title", "#9AA4B2"));
        valueColor = color(s(cfg, "styles.value", "#28E0A6"));
        ringBack   = color(s(cfg, "styles.ring.bg", "#1B2230"));
        ringFore   = color(s(cfg, "styles.ring.fg", "#28E0A6"));

        double w = d(cfg, "layout.width", canvas.getWidth());
        double h = d(cfg, "layout.height", canvas.getHeight());
        canvas.setWidth(w);
        canvas.setHeight(h);
        setPrefSize(w, h);

        simEnabled = b(cfg, "simulate.enabled", false);
        simMin = d(cfg, "simulate.min", min);
        simMax = d(cfg, "simulate.max", max);
        simHz = d(cfg, "simulate.speed_hz", 0.5);
        phase = 0;
        lastNs = 0;
        if (simEnabled) simTimer.start(); else simTimer.stop();

        draw();
    }

    // --- Value Update ---
    public void setValue(double v) {
        double cv = Math.max(min, Math.min(max, v));
        value.set(cv);
    }

    public double getValue() { return value.get(); }
    public DoubleProperty valueProperty() { return value; }

    // --- Layout handling ---
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
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    // --- Draw ---
    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth();
        double H = canvas.getHeight();

        g.setFill(bg);
        g.fillRect(0, 0, W, H);

        // --- Title ---
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        g.setFill(titleColor);
        g.setFont(Font.font(Math.max(12, H * 0.12)));
        g.fillText(title, W / 2, H * 0.1);

        // --- Value ---
        String txt = fmt(value.get(), format) + suffix;
        g.setTextBaseline(VPos.CENTER);
        g.setFill(valueColor);
        g.setFont(Font.font(Math.max(12, H * 0.36)));
        g.fillText(txt, W / 2, H * 0.52);

        // --- Ring ---
        double cx = W * 0.5;
        double cy = H * 0.55;
        double R = Math.min(W, H) * 0.42;
        double thickness = Math.max(6, R * 0.12);
        double startAngle = 220; // degrees
        double spanAngle = 260;  // degrees

        double pct = (value.get() - min) / Math.max(1e-9, (max - min));
        pct = Math.max(0, Math.min(1, pct));

        g.setLineWidth(thickness);
        g.setStroke(ringBack);
        g.strokeArc(cx - R, cy - R, R * 2, R * 2, startAngle, -spanAngle, null);

        g.setStroke(ringFore);
        g.strokeArc(cx - R, cy - R, R * 2, R * 2, startAngle, -spanAngle * pct, null);
    }

    // --- Config Helpers ---
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
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignore) {}
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

    private static String fmt(double v, String pattern) {
        int dp = 0;
        int dot = pattern.indexOf('.');
        if (dot >= 0) dp = pattern.length() - dot - 1;
        return String.format("%." + dp + "f", v);
    }
}


