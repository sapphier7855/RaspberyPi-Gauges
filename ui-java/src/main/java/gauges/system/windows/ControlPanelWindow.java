package gauges.system.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import gauges.system.WindowManager; // WindowManager.WindowSceneController, WindowManager.ShellFacade
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * ControlPanelWindow — exactly two vertically stacked mode buttons.
 * Labels are always "Mode 1" and "Mode 2" (forced, not dynamic).
 * Emits "mode1" / "mode2" via the onModeRequest callback.
 * Implements full WindowSceneController lifecycle expected by WindowManager.
 */
public final class ControlPanelWindow implements WindowManager.WindowSceneController {

    // --- wiring expected by WindowManager ---
    private Consumer<String> onModeRequest = m -> {};
    // kept only for compatibility; no longer affects labels/IDs
    private final List<String> availableModes = new ArrayList<>();

    // --- lifecycle/context (optional) ---
    private Stage stage;
    @SuppressWarnings("unused")
    private WindowManager.ShellFacade shell;

    // --- UI state ---
    private final BorderPane root = new BorderPane();
    private Button btnMode1;
    private Button btnMode2;

    /** No-args ctor (WindowManager uses this). */
    public ControlPanelWindow() {}

    // ---------------- WindowSceneController impl ----------------

    /** WindowManager calls this to obtain the UI root. */
    @Override
    public Parent createContent(String windowKey) {
        // Build UI once (idempotent if called again)
        if (root.getCenter() == null) {
            VBox content = new VBox(20);
            content.setPadding(new Insets(20));
            content.setAlignment(Pos.CENTER);

            Label header = new Label("Modes");
            header.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

            btnMode1 = new Button("Mode 1");
            btnMode2 = new Button("Mode 2");

            btnMode1.setMinWidth(160);
            btnMode2.setMinWidth(160);
            btnMode1.setPrefHeight(40);
            btnMode2.setPrefHeight(40);

            // Centered stacked layout
            VBox buttonsBox = new VBox(15, btnMode1, btnMode2);
            buttonsBox.setAlignment(Pos.CENTER);

            btnMode1.setOnAction(e -> onModeRequest.accept("mode1"));
            btnMode2.setOnAction(e -> onModeRequest.accept("mode2"));

            content.getChildren().addAll(header, buttonsBox);
            root.setCenter(content);
        }
        return root;
    }

    /** Called when WindowManager has mounted the scene into a Stage/Shell. */
    @Override
    public void onMounted(Stage stage, WindowManager.ShellFacade shell) {
        this.stage = stage;
        this.shell = shell;
    }

    /** Called right before the window is shown. */
    @Override
    public void onBeforeReveal() {}

    /** Called right after the window is shown. */
    @Override
    public void onAfterReveal() {}

    /** Called on close; release resources if needed. */
    @Override
    public void onClose() {}

    // ---------------- Legacy API expected by WindowManager ----------------

    /** Compatibility stub — modes list ignored (labels forced). */
    public void setAvailableModes(List<String> modes) {
        availableModes.clear();
        if (modes != null) availableModes.addAll(modes);
        if (btnMode1 != null) btnMode1.setText("Mode 1");
        if (btnMode2 != null) btnMode2.setText("Mode 2");
    }

    /** WindowManager wires the mode change callback here. */
    public void setOnModeRequest(Consumer<String> handler) {
        this.onModeRequest = (handler != null) ? handler : (m -> {});
    }
}


