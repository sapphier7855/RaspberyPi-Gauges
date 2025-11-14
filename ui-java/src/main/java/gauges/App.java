package gauges;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Path logFile;
            Path masterLog;
            Path sessionDir = null;

            Path explicitLog = resolveOptionalPath("gauges.log.file", "GAUGES_LOG_FILE");
            Path explicitMaster = resolveOptionalPath("gauges.log.master", "GAUGES_LOG_MASTER");
            Path explicitDir = resolveOptionalPath("gauges.log.dir", "GAUGES_LOG_DIR");

            Path baseDir = explicitDir != null ? explicitDir : Paths.get("logs");

            try {
                if (explicitLog != null) {
                    logFile = explicitLog;
                } else {
                    sessionDir = prepareSessionDirectory(baseDir);
                    logFile = sessionDir.resolve("app.log");
                }

                if (explicitMaster != null) {
                    masterLog = explicitMaster;
                } else {
                    Path masterBase = explicitDir != null
                            ? baseDir
                            : sessionDir != null ? sessionDir.getParent() : baseDir;
                    if (masterBase == null) {
                        masterBase = logFile.toAbsolutePath().getParent();
                    }
                    if (masterBase == null) {
                        masterBase = Paths.get(".");
                    }
                    masterLog = masterBase.resolve("master.log");
                }
            } catch (IOException ioe) {
                throw new RuntimeException("Failed to prepare log directory", ioe);
            }

            Logger.start(logFile, masterLog, true);
            if (sessionDir != null) {
                System.out.println("[log] sessionDir=" + sessionDir);
            }
            System.out.println("[log] file=" + logFile + ", master=" + masterLog);
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

    private static Path resolveOptionalPath(String sysPropKey, String envKey) {
        String value = firstNonBlank(System.getProperty(sysPropKey), System.getenv(envKey));
        if (value == null) return null;
        return Paths.get(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private static Path prepareSessionDirectory(Path baseDir) throws IOException {
        Files.createDirectories(baseDir);

        int counter = nextDirectoryIndex(baseDir);
        Path session = baseDir.resolve("log-" + counter);
        Files.createDirectories(session);
        return session;
    }

    private static int nextDirectoryIndex(Path baseDir) throws IOException {
        int candidate = 1;
        while (Files.exists(baseDir.resolve("log-" + candidate))) {
            candidate++;
        }
        return candidate;
    }
    private static Boolean parseBool(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")) return true;
        if (v.equalsIgnoreCase("false")|| v.equals("0") || v.equalsIgnoreCase("no"))  return false;
        return null;
    }
}


