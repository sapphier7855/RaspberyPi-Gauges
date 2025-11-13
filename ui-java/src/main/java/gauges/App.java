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
            Path storageMirror = resolveOptionalPath("gauges.log.storage", "GAUGES_LOG_STORAGE");
            String pipelineOverride = firstNonBlank(
                    System.getProperty("gauges.log.dataPipelineDir"),
                    System.getenv("GAUGES_LOG_DATA_PIPELINE"));
            Path dataPipelineDir = null;

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

                if (storageMirror == null) {
                    storageMirror = findStorageMirrorFallback();
                }

                if (sessionDir != null && System.getProperty("gauges.log.sessionDir") == null) {
                    System.setProperty("gauges.log.sessionDir", sessionDir.toString());
                }

                if (pipelineOverride != null) {
                    dataPipelineDir = Paths.get(pipelineOverride);
                    if (System.getProperty("gauges.log.dataPipelineDir") == null) {
                        System.setProperty("gauges.log.dataPipelineDir", dataPipelineDir.toString());
                    }
                } else {
                    Path pipelineBase;
                    if (sessionDir != null) {
                        pipelineBase = sessionDir;
                    } else if (explicitDir != null) {
                        pipelineBase = baseDir;
                    } else {
                        pipelineBase = logFile.toAbsolutePath().getParent();
                        if (pipelineBase == null) {
                            pipelineBase = baseDir;
                        }
                    }
                    dataPipelineDir = pipelineBase.resolve("data-pipline");
                    System.setProperty("gauges.log.dataPipelineDir", dataPipelineDir.toString());
                }
            } catch (IOException ioe) {
                throw new RuntimeException("Failed to prepare log directory", ioe);
            }

            Logger.start(logFile, masterLog, true);
            if (sessionDir != null) {
                System.out.println("[log] sessionDir=" + sessionDir);
            }
            System.out.println("[log] file=" + logFile + ", master=" + masterLog);
            if (dataPipelineDir != null) {
                System.out.println("[log] data-pipline=" + dataPipelineDir);
            }
            if (storageMirror != null) {
                try {
                    Logger.addMirror(storageMirror);
                    System.out.println("[log] storage=" + storageMirror);
                } catch (Exception ex) {
                    System.err.println("[log][warn] unable to attach storage log '" + storageMirror + "': " + ex.getMessage());
                }
            }
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

    private static Path findStorageMirrorFallback() {
        Path start = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 6 && start != null; depth++) {
            Path candidate = start.resolve("Storage");
            if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
                return candidate;
            }
            start = start.getParent();
        }

        Path fallback = Paths.get("Storage");
        Path parent = fallback.getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return fallback;
        }
        return null;
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


