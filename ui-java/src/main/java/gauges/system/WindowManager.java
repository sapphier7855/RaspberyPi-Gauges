package gauges.system;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import gauges.system.windows.ControlPanelWindow;
import gauges.system.windows.DashboardWindow;
import gauges.system.windows.SingleGaugeWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * WindowManager
 *
 * Builds and shows the three app windows and hands each a ShellFacade.
 * Adds a visible diagnostic shell so windows are never "blank" during boot.
 */
public final class WindowManager {

    // ---------------------------------------------------------------------
    // Contracts
    // ---------------------------------------------------------------------
    public interface WindowSceneController {
        Parent createContent(String initialModeKey);
        void onMounted(Stage stage, ShellFacade shell);
        void onBeforeReveal();
        void onAfterReveal();
        void onClose();
    }

    public static final class ShellFacade {
        private final Stage stage;
        private final StackPane overlaySlot;

        ShellFacade(Stage stage, StackPane overlaySlot) {
            this.stage = stage;
            this.overlaySlot = overlaySlot;
        }
        public Stage stage() { return stage; }
        public StackPane overlaySlot() { return overlaySlot; }
        public void mountOverlay(javafx.scene.Node node) {
            if (node != null) overlaySlot.getChildren().add(node);
        }
        public void clearOverlays() { overlaySlot.getChildren().clear(); }
        public void setOverlaysMouseTransparent(boolean v) { overlaySlot.setMouseTransparent(v); }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------
    private final Consumer<String> onModeRequest; // nullable
    private final Map<String, Stage> stages = new HashMap<>();
    private final Map<String, WindowSceneController> ctrls = new HashMap<>();

    // ---------------------------------------------------------------------
    // Ctors
    // ---------------------------------------------------------------------
    public WindowManager(Consumer<String> onModeRequest) { this.onModeRequest = onModeRequest; }
    /** Needed by BootCoordinator (new WindowManager()). */
    public WindowManager() { this(null); }

    // ---------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------
    /** Debug-friendly: guarantees visible windows with default buttons. */
    public void createAndShowAllWindows_SafeShow() {
        startAll("mode1", List.of("mode1", "mode2"));
    }

    /**
     * Build and show all app windows. Must run on FX thread.
     */
    public void startAll(String initialModeKey, List<String> controlPanelModes) {
        ensureFxThread();

        // --- Dashboard ---
        DashboardWindow dashboard = new DashboardWindow();
        Stage dashStage = new Stage(StageStyle.DECORATED);
        mount("dashboard", dashboard, dashStage, initialModeKey, 1280, 720);
        dashStage.setTitle("Gauges — Dashboard");
        placeWindow(dashStage, 100, 80);

        // --- Single Gauge ---
        SingleGaugeWindow single = new SingleGaugeWindow();
        Stage singleStage = new Stage(StageStyle.DECORATED);
        mount("single", single, singleStage, initialModeKey, 640, 640);
        singleStage.setTitle("Gauges — Single");
        placeWindow(singleStage, dashStage.getX() + dashStage.getWidth() + 12, dashStage.getY());

        // --- Control Panel ---
        ControlPanelWindow control = new ControlPanelWindow();
        if (controlPanelModes != null && !controlPanelModes.isEmpty()) {
            control.setAvailableModes(controlPanelModes);
        }
        if (onModeRequest != null) {
            control.setOnModeRequest(onModeRequest);
        }
        Stage controlStage = new Stage(StageStyle.UTILITY);
        mount("control", control, controlStage, null, 420, 360);
        controlStage.setTitle("Gauges — Control Panel");
        placeWindow(controlStage, dashStage.getX(), dashStage.getY() + dashStage.getHeight() + 12);

        // Show in order
        reveal("dashboard");
        reveal("single");
        reveal("control");
    }

    // ---------------------------------------------------------------------
    // Mounting
    // ---------------------------------------------------------------------
    private void mount(String key,
                       WindowSceneController controller,
                       Stage stage,
                       String initialModeKey,
                       double width,
                       double height) {

        Objects.requireNonNull(key, "window key");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(stage, "stage");

        // 1) Ask the window for its content (may be null while mode wiring happens)
        Parent contentRoot = controller.createContent(initialModeKey);

        // 2) Diagnostic shell guarantees a visible background + frame + tag
        StackPane diagnosticShell = new StackPane();
        diagnosticShell.setStyle("-fx-background-color: rgba(20,20,20,0.96);");
        diagnosticShell.setBorder(new Border(new BorderStroke(
                Color.web("#3A7AFE"), BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(1)
        )));

        Label tag = new Label("SAFE-SHOW: " + key);
        tag.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-opacity: 0.9;");
        StackPane.setAlignment(tag, javafx.geometry.Pos.TOP_LEFT);
        StackPane.setMargin(tag, new Insets(6, 0, 0, 6));

        // 3) Fallback content if controller returns null
        if (contentRoot == null) {
            StackPane placeholder = new StackPane(new Label("Empty content: " + key));
            placeholder.setStyle("-fx-text-fill: white;");
            contentRoot = placeholder;
        }

        // 4) Find or create overlay slot
        StackPane overlaySlot = findOverlaySlot(controller);
        if (overlaySlot == null) {
            overlaySlot = new StackPane();
            overlaySlot.setMouseTransparent(true);
        }

        // 5) Build visual stack: [content][overlay][corner tag]
        diagnosticShell.getChildren().addAll(contentRoot, overlaySlot, tag);

        // 6) Scene + stage
        Scene scene = new Scene(diagnosticShell, width, height);
        // Avoid transparent scenes while debugging visibility
        // scene.setFill(Color.BLACK);
        stage.initStyle(StageStyle.DECORATED);
        stage.setScene(scene);

        // 7) Hand facade & track
        ShellFacade shell = new ShellFacade(stage, overlaySlot);
        controller.onMounted(stage, shell);

        stages.put(key, stage);
        ctrls.put(key, controller);
    }

    /** Attempts public no-arg getOverlaySlot():StackPane on the controller. */
    private StackPane findOverlaySlot(WindowSceneController controller) {
        try {
            var m = controller.getClass().getMethod("getOverlaySlot");
            Object obj = m.invoke(controller);
            if (obj instanceof StackPane sp) return sp;
        } catch (Exception ignore) {}
        return null;
    }

    // ---------------------------------------------------------------------
    // Show/Hide/Focus
    // ---------------------------------------------------------------------
    public void reveal(String key) {
        ensureFxThread();
        Stage s = stages.get(key);
        WindowSceneController c = ctrls.get(key);
        if (s == null || c == null) return;
        c.onBeforeReveal();
        s.show();
        s.toFront();
        c.onAfterReveal();
    }

    public void hide(String key) {
        ensureFxThread();
        Stage s = stages.get(key);
        if (s != null) s.hide();
    }

    public void close(String key) {
        ensureFxThread();
        Stage s = stages.remove(key);
        WindowSceneController c = ctrls.remove(key);
        if (c != null) c.onClose();
        if (s != null) s.close();
    }

    public void focus(String key) {
        ensureFxThread();
        Stage s = stages.get(key);
        if (s != null) { s.show(); s.toFront(); }
    }

    // ---------------------------------------------------------------------
    // External helpers
    // ---------------------------------------------------------------------
    public Stage getStage(String key) { return stages.get(key); }
    public StackPane tryGetOverlaySlot(String key) {
        WindowSceneController c = ctrls.get(key);
        return (c != null) ? findOverlaySlot(c) : null;
    }

    // ---------------------------------------------------------------------
    // Layout/thread utils
    // ---------------------------------------------------------------------
    private static void placeWindow(Stage s, double x, double y) {
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double nx = Math.max(vb.getMinX(), Math.min(x, vb.getMaxX() - s.getWidth()));
        double ny = Math.max(vb.getMinY(), Math.min(y, vb.getMaxY() - s.getHeight()));
        s.setX(nx);
        s.setY(ny);
    }

    private static void ensureFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("WindowManager methods must be called on the JavaFX Application Thread");
        }
    }
}


