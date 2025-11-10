package gauges;

import java.nio.file.Paths;
import java.util.Arrays;

import gauges.system.Logger;
import javafx.application.Application;
import javafx.stage.Stage;

public class App extends Application {

    // Default OFF — can be overridden by args, -Dlog, or ENV LOG
    private static boolean log = true;

    public static void main(String[] args) {
        log = resolveLogFlag(args, log);
        if (log) {
            Logger.start(Paths.get("logs/app.log"), true);
            try { Logger.quietJavaFX(true); } catch (Throwable ignored) {}
        }
        System.out.println("Logging enabled: " + log);
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // Do NOT: primaryStage.show();
        new gauges.system.BootCoordinator().start(primaryStage);
    }

    // --- logging flag helper ---
    private static boolean resolveLogFlag(String[] args, boolean defaultValue) {
        String sys = System.getProperty("log");
        String env = System.getenv("LOG");
        System.out.println("[log] sources -> args=" + Arrays.toString(args) +
                           ", -Dlog=" + sys + ", LOG=" + env + ", default=" + defaultValue);
        if (args != null) {
            for (String a : args) {
                if ("--log".equalsIgnoreCase(a)) return true;
                if ("--no-log".equalsIgnoreCase(a)) return false;
            }
        }
        Boolean b = parseBool(sys);
        if (b != null) return b;
        b = parseBool(env);
        if (b != null) return b;
        return defaultValue;
    }
    private static Boolean parseBool(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")) return true;
        if (v.equalsIgnoreCase("false")|| v.equals("0") || v.equalsIgnoreCase("no"))  return false;
        return null;
    }
}


