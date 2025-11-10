package gauges.ui.loading;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * ControlSplashView — wide ribbon with glowing dots (pulsing)
 *
 * - Solid black background
 * - Thin, wide ribbon across the middle
 * - Subtle gray track + blue dots that pulse (fade in/out) in place
 * - Optional progress fill tied to setProgress(..)
 * - Fade-in on mount, fade-out on ready
 */
public class ControlSplashView extends StackPane {
    // Visual constants
    private static final double RIBBON_HEIGHT = 56;        // height of the ribbon
    private static final double RIBBON_ARC    = 20;        // rounded corners
    private static final double DOT_RADIUS    = 6;         // base dot size
    private static final int    DOT_COUNT     = 9;         // number of dots
    private static final double DOT_SPACING   = 48;        // min spacing between dots
    private static final Duration PULSE_PERIOD  = Duration.seconds(1.4); // one fade-in/out

    private static final Color  BG_DIM        = Color.BLACK;                // solid black
    private static final Color  TRACK_GRAY    = Color.web("#30343a");
    private static final Color  TRACK_EDGE    = Color.web("#4a4f57");
    private static final Color  BLUE_ACCENT   = Color.web("#3aa0ff");
    private static final Color  TEXT_COLOR    = Color.web("#d7e6ff");

    // Nodes
    private final StackPane ribbon = new StackPane();
    private final Rectangle track  = new Rectangle();
    private final Rectangle trackEdge = new Rectangle();
    private final Rectangle progressFill = new Rectangle();
    private final Group dotsGroup  = new Group();
    private final Label title      = new Label("Control Panel");
    private final Label subtitle   = new Label("Warming up modules…");

    private Timeline dotsLoop;

    // progress in [0..1]
    private double progress = 0;

    public ControlSplashView() {
        setPickOnBounds(true);
        setBackground(new Background(new BackgroundFill(BG_DIM, CornerRadii.EMPTY, Insets.EMPTY)));
        setAlignment(Pos.CENTER);
        setPadding(new Insets(32));
        setCache(true);
        setCacheHint(CacheHint.SPEED);

        // Ribbon container
        ribbon.setMinHeight(RIBBON_HEIGHT);
        ribbon.setPrefHeight(RIBBON_HEIGHT);
        ribbon.setMaxHeight(RIBBON_HEIGHT);
        ribbon.setOpacity(0.0); // fade-in after mount

        // Track (gray) with soft edges
        track.setArcWidth(RIBBON_ARC);
        track.setArcHeight(RIBBON_ARC);
        track.setFill(TRACK_GRAY);

        // Slight edge highlight to elevate the ribbon
        trackEdge.setArcWidth(RIBBON_ARC);
        trackEdge.setArcHeight(RIBBON_ARC);
        trackEdge.setFill(Color.TRANSPARENT);
        trackEdge.setStroke(TRACK_EDGE);
        trackEdge.setStrokeWidth(1.5);

        // Progress fill (under dots); clipped to track bounds
        progressFill.setArcWidth(RIBBON_ARC);
        progressFill.setArcHeight(RIBBON_ARC);
        progressFill.setFill(BLUE_ACCENT.deriveColor(0,1,1,0.35));

        // clip to ribbon width/height
        Rectangle clip = new Rectangle();
        clip.setArcWidth(RIBBON_ARC);
        clip.setArcHeight(RIBBON_ARC);
        ribbon.setClip(clip);

        // Title + subtitle above ribbon
        VBox labels = new VBox(6);
        title.setTextFill(TEXT_COLOR);
        title.setFont(Font.font("Inter", FontWeight.SEMI_BOLD, 20));
        subtitle.setTextFill(TEXT_COLOR.deriveColor(0,1,1,0.85));
        subtitle.setFont(Font.font("Inter", 14));
        labels.getChildren().addAll(title, subtitle);
        labels.setAlignment(Pos.CENTER);
        labels.setOpacity(0.0);

        // Layering: [labels], [ribbon -> track + progress + dots + edge]
        StackPane ribbonLayers = new StackPane(track, progressFill, dotsGroup, trackEdge);
        ribbon.getChildren().add(ribbonLayers);

        // Layout wrapper
        VBox layout = new VBox(16, labels, ribbon);
        layout.setAlignment(Pos.CENTER);
        getChildren().add(layout);

        // Bind sizes to width of this splash (minus padding)
        DoubleBinding contentWidth = widthProperty().subtract(getPadding().getLeft() + getPadding().getRight());
        track.widthProperty().bind(contentWidth.multiply(0.88)); // 88% of available width
        track.setHeight(RIBBON_HEIGHT);
        trackEdge.widthProperty().bind(track.widthProperty());
        trackEdge.heightProperty().bind(track.heightProperty());
        progressFill.heightProperty().bind(track.heightProperty());

        // Keep the clip in sync
        clip.widthProperty().bind(track.widthProperty());
        clip.heightProperty().bind(track.heightProperty());

        // Center the ribbon
        ribbon.maxWidthProperty().bind(track.widthProperty());
        ribbon.minWidthProperty().bind(track.widthProperty());
        ribbon.prefWidthProperty().bind(track.widthProperty());

        // Initialize dots after we know track width
        track.widthProperty().addListener((obs, oldW, newW) -> rebuildDots(newW.doubleValue()));

        // Gentle bloom on dots to enhance glow
        Bloom bloom = new Bloom(0.15);
        dotsGroup.setEffect(bloom);

        // Fade in labels + ribbon when created
        Platform.runLater(this::playIntro);
    }

    /* --------------------------------- API ---------------------------------- */

    /** Mount into an overlay slot (e.g., from WindowManager) with a fade-in. */
    public static ControlSplashView mountInto(StackPane overlaySlot) {
        ControlSplashView view = new ControlSplashView();
        StackPane.setAlignment(view, Pos.CENTER);
        overlaySlot.getChildren().add(view);
        return view;
    }

    /** Optional: set the header and subheader text. */
    public void setLabels(String header, String subtext) {
        if (header != null) title.setText(header);
        if (subtext != null) subtitle.setText(subtext);
    }

    /** Update progress in [0..1]. This expands the blue fill under the dots. */
    public void setProgress(double p) {
        progress = clamp01(p);
        double w = track.getWidth();
        progressFill.setWidth(w * progress);
    }

    /** Starts dot pulsing loop (auto-starts on intro). */
    public void play() {
        if (dotsLoop != null) dotsLoop.play();
    }

    /** Pauses dot pulsing loop. */
    public void pause() {
        if (dotsLoop != null) dotsLoop.pause();
    }

    /** Fade out and remove from the given overlay slot. */
    public void fadeOutAndRemove(Pane overlaySlot) {
        pause();
        FadeTransition ft = new FadeTransition(Duration.millis(400), this);
        ft.setFromValue(getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(e -> overlaySlot.getChildren().remove(this));
        ft.play();
    }

    /* ---------------------------- Private helpers --------------------------- */

    private void playIntro() {
        // Fade in this whole overlay quickly
        setOpacity(0);
        FadeTransition overlayIn = new FadeTransition(Duration.millis(220), this);
        overlayIn.setToValue(1.0);

        // Fade + slide labels slightly for polish
        Node labels = ((VBox)getChildren().get(0)).getChildren().get(0);
        labels.setTranslateY(6);
        Timeline labelsIn = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(labels.opacityProperty(), 0.0, Interpolator.EASE_OUT),
                new KeyValue(labels.translateYProperty(), 6, Interpolator.EASE_OUT)
            ),
            new KeyFrame(Duration.millis(360),
                new KeyValue(labels.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                new KeyValue(labels.translateYProperty(), 0, Interpolator.EASE_OUT)
            )
        );

        // Fade in the ribbon after labels
        FadeTransition ribbonIn = new FadeTransition(Duration.millis(260), ribbon);
        ribbonIn.setFromValue(0.0);
        ribbonIn.setToValue(1.0);

        overlayIn.setOnFinished(e -> labelsIn.play());
        labelsIn.setOnFinished(e -> {
            ribbonIn.play();
            ribbonIn.setOnFinished(ev -> startDotsPulse());
        });

        overlayIn.play();
    }

    private void rebuildDots(double width) {
        dotsGroup.getChildren().clear();
        double centerY = RIBBON_HEIGHT / 2.0;

        // Build dot nodes spaced across the track; each dot pulses in place
        for (int i = 0; i < DOT_COUNT; i++) {
            Circle dot = new Circle(DOT_RADIUS);
            dot.setFill(BLUE_ACCENT);
            // layered glow
            DropShadow glow1 = new DropShadow(12, BLUE_ACCENT.deriveColor(0,1,1,0.55));
            glow1.setInput(new DropShadow(24, BLUE_ACCENT.deriveColor(0,1,1,0.25)));
            dot.setEffect(glow1);
            dot.setCache(true);
            dot.setCacheHint(CacheHint.SPEED);

            // Even spacing across width
            double spacing = Math.max(DOT_SPACING, width / (DOT_COUNT + 1));
            dot.setTranslateX((-width / 2.0) + spacing * (i + 1));
            dot.setTranslateY(centerY - DOT_RADIUS / 2.0);
            dotsGroup.getChildren().add(dot);
        }

        // Restart the pulse with the new width
        startDotsPulse();

        // Also update progress width to current track width
        setProgress(progress);
    }

    // Legacy name kept for compatibility, redirect to pulse
    private void startDotsLoop() { startDotsPulse(); }

    private void startDotsPulse() {
        if (!isVisible() || getScene() == null) return;
        if (dotsGroup.getChildren().isEmpty()) return;

        if (dotsLoop != null) {
            dotsLoop.stop();
        }

        // Gentle opacity pulse per dot, with staggered phase
        ParallelTransition pulse = new ParallelTransition();
        int i = 0;
        for (Node n : dotsGroup.getChildren()) {
            Circle dot = (Circle) n;
            FadeTransition ft = new FadeTransition(PULSE_PERIOD, dot);
            ft.setFromValue(0.20);
            ft.setToValue(1.0);
            ft.setAutoReverse(true);
            ft.setCycleCount(Animation.INDEFINITE);
            ft.setInterpolator(Interpolator.EASE_IN);
            ft.setDelay(Duration.millis(i * (PULSE_PERIOD.toMillis() / DOT_COUNT)));
            pulse.getChildren().add(ft);
            i++;
        }

        pulse.play();

        // Lightweight handle to keep lifecycle parity with prior implementation
        dotsLoop = new Timeline(new KeyFrame(Duration.seconds(1)));
        dotsLoop.setCycleCount(Animation.INDEFINITE);
        dotsLoop.play();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}


