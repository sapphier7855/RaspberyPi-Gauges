package gauges.ui.loading;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * GaugeSplashView — Single Gauge loading splash (round)
 *
 * Design:
 * - Black background (forced)
 * - Solid ring that stays the same size
 * - The ring’s glow brightens and dims (no scaling)
 * - Centered "Loading" text
 *
 * No arcs are used (so no chance of a stray center line).
 */
public final class GaugeSplashView extends StackPane {

    private static final double PREFERRED_SIZE = 320;

    private final Circle baseRing;   // steady outline
    private final Circle glowRing;   // same radius; animated glow
    private final Text   label;

    private final DropShadow glow;   // animated intensity
    private final Timeline   pulseTl;

    public GaugeSplashView() {
        // Force black background
        setStyle("-fx-background-color: black;");
        setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPrefSize(PREFERRED_SIZE, PREFERRED_SIZE);
        setAlignment(Pos.CENTER);
        setCache(true);
        setCacheHint(CacheHint.SPEED);

        // --- Visual constants
        double ringStroke     = 6.0;
        Color ringColor       = Color.rgb(200, 205, 215, 0.35); // subtle base ring
        Color glowStrokeColor = Color.rgb(80, 160, 255, 1.00);  // blue ring (we’ll fade its opacity)

        // Base solid ring (does not animate)
        baseRing = new Circle();
        baseRing.setStroke(ringColor);
        baseRing.setStrokeWidth(ringStroke);
        baseRing.setFill(Color.TRANSPARENT);

        // Glow effect to animate on the glow ring
        glow = new DropShadow(BlurType.GAUSSIAN, Color.rgb(80, 160, 255, 0.90), 22, 0.60, 0, 0);

        // Glow ring (same radius as baseRing). We animate ONLY brightness via opacity + glow radius.
        glowRing = new Circle();
        glowRing.setStroke(glowStrokeColor);
        glowRing.setStrokeWidth(ringStroke);
        glowRing.setFill(Color.TRANSPARENT);
        glowRing.setEffect(glow);
        glowRing.setOpacity(0.35); // start dim

        // Center label
        label = new Text("Loading");
        label.setFill(Color.WHITE);
        label.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 18));

        StackPane ringLayer = new StackPane(baseRing, glowRing);
        ringLayer.setPickOnBounds(false);
        getChildren().addAll(ringLayer, label);

        // --- Pulse animation: brighten/dim (no scaling)
        // We vary: glowRing.opacity (stroke brightness) and glow.radius (halo size)
        pulseTl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(glowRing.opacityProperty(), 0.30, Interpolator.EASE_BOTH),
                new KeyValue(glow.radiusProperty(), 16.0,        Interpolator.EASE_BOTH)
            ),
            new KeyFrame(Duration.millis(1150),
                new KeyValue(glowRing.opacityProperty(), 0.95, Interpolator.EASE_BOTH),
                new KeyValue(glow.radiusProperty(), 34.0,      Interpolator.EASE_BOTH)
            ),
            new KeyFrame(Duration.millis(2300),
                new KeyValue(glowRing.opacityProperty(), 0.30, Interpolator.EASE_BOTH),
                new KeyValue(glow.radiusProperty(), 16.0,      Interpolator.EASE_BOTH)
            )
        );
        pulseTl.setCycleCount(Animation.INDEFINITE);

        // Size to parent
        widthProperty().addListener((o, a, b) -> layoutRings());
        heightProperty().addListener((o, a, b) -> layoutRings());

        // Initialize radii up front to avoid any 0-size artifacts
        initRadiiFor(PREFERRED_SIZE);

        // Auto-start the pulse once in the scene
        sceneProperty().addListener((o, oldS, newS) -> {
            if (newS != null) {
                Platform.runLater(() -> {
                    layoutRings();
                    play();
                });
            }
        });
    }

    private void initRadiiFor(double size) {
        double radius = Math.max(60, size * 0.33); // clamp to a sensible minimum
        baseRing.setRadius(radius);
        glowRing.setRadius(radius);
    }

    private void layoutRings() {
        double size = Math.min(getWidth(), getHeight());
        if (size <= 0 || Double.isNaN(size)) return;
        initRadiiFor(size);
    }

    // Controls
    public void play() {
        if (pulseTl.getStatus() != Animation.Status.RUNNING) pulseTl.play();
    }

    public void stop() {
        pulseTl.stop();
    }

    public void fadeOut(Duration duration, Runnable onFinished) {
        FadeTransition ft = new FadeTransition(duration, this);
        ft.setFromValue(getOpacity());
        ft.setToValue(0.0);
        ft.setInterpolator(Interpolator.EASE_BOTH);
        ft.setOnFinished(e -> {
            stop();
            if (onFinished != null) onFinished.run();
        });
        ft.play();
    }
}


