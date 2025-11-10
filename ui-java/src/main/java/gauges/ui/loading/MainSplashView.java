package gauges.ui.loading;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Self-contained splash view with title, subtitle, and progress bar.
 * Replaces the old Slots-based version so it compiles cleanly.
 *
 * Public API kept tiny for StartupManager:
 *   - setTitle(String)
 *   - setSubtitle(String)
 *   - setProgress(double 0..1)
 *
 * This class extends StackPane, so you can mount it directly in your overlay slot.
 */
public final class MainSplashView extends StackPane {

    private final Label title = new Label("Starting…");
    private final Label subtitle = new Label("");
    private final ProgressBar progress = new ProgressBar(0);

    public MainSplashView() {
        // Background layer
        Region bg = new Region();
        bg.setBackground(new Background(new BackgroundFill(Color.web("#0b0b0b"), CornerRadii.EMPTY, Insets.EMPTY)));
        bg.setOpacity(1.0);

        // Optional glass card in the middle
        VBox card = new VBox(14);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(24, 32, 24, 32));
        card.setMaxWidth(520);

        // Card backdrop
        StackPane cardBg = new StackPane();
        Rectangle r = new Rectangle();
        r.widthProperty().bind(card.maxWidthProperty());
        r.setHeight(160);
        r.setArcWidth(16);
        r.setArcHeight(16);
        r.setFill(Color.web("#15151580")); // semi-transparent
        r.setStroke(Color.web("#2a2a2a"));
        r.setStrokeWidth(1.0);
        r.setEffect(new GaussianBlur(2));
        cardBg.getChildren().add(r);
        cardBg.setMouseTransparent(true);

        // Title
        title.setTextFill(Color.web("#e8e8e8"));
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: 700;");

        // Subtitle
        subtitle.setTextFill(Color.web("#b5b5b5"));
        subtitle.setStyle("-fx-font-size: 14px;");

        // Progress bar
        progress.setPrefWidth(420);
        progress.setMinWidth(420);
        progress.setMaxWidth(420);
        progress.setProgress(0);
        progress.setStyle("" +
                "-fx-accent: #3da9f5;" +
                "-fx-control-inner-background: #2a2a2a;" +
                "-fx-background-insets: 0;" +
                "-fx-padding: 0.25em;");

        // Stack elements
        StackPane content = new StackPane();
        VBox inner = new VBox(8, title, subtitle, progress);
        inner.setAlignment(Pos.CENTER);
        content.getChildren().addAll(cardBg, inner);

        // Root layout
        setAlignment(Pos.CENTER);
        getChildren().addAll(bg, content);

        // Layout hints
        setPadding(new Insets(32));
        setCache(true);
        setCacheHint(CacheHint.SPEED);
    }

    // --- Public API used by StartupManager -------------------------------------

    public void setTitle(String text) {
        if (text == null) text = "";
        title.setText(text);
    }

    public void setSubtitle(String text) {
        if (text == null) text = "";
        subtitle.setText(text);
    }

    /** Range will be clamped to [0..1]. */
    public void setProgress(double value) {
        if (Double.isNaN(value)) value = 0;
        progress.setProgress(Math.max(0, Math.min(1, value)));
    }
}


