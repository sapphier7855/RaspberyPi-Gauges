package gauges.system;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Central logger that captures all terminal output (System.out & System.err)
 * and writes it to a file in the format:
 *
 * yyyy-MM-dd HH:mm:ss.SSS | LEVEL   | Class.method(File:Line) | message
 *
 * Usage (early in App.java main or init):
 *   Logger.start(Paths.get("logs/app.log"));
 *   // or Logger.start(Paths.get("logs/app.log"), Paths.get("logs/master.log"));
 *   // (all variants create parent directories if needed)
 *   Logger.quietJavaFX(dev);                  // <<< add this to suppress JavaFX chatter
 *   // ... your app ...
 *   Logger.stop(); // optional, also done by shutdown hook
 */
public final class Logger {

    // --- Public API ---------------------------------------------------------

    /** Start logging everything printed to the terminal into the given file. */
    public static synchronized void start(Path logFile) {
        start(logFile, null, /*echoMinimalToStderr*/ false);
    }

    /**
     * Start logging. If echoMinimalToStderr=true, only a tiny banner is echoed
     * to the real terminal; everything else goes to the log.
     */
    public static synchronized void start(Path logFile, boolean echoMinimalToStderr) {
        start(logFile, null, echoMinimalToStderr);
    }

    /** Start logging into a primary log file and mirror every entry to masterLogFile. */
    public static synchronized void start(Path logFile, Path masterLogFile) {
        start(logFile, masterLogFile, /*echoMinimalToStderr*/ false);
    }

    /**
     * Start logging into a primary log file and mirror entries into masterLogFile when provided.
     */
    public static synchronized void start(Path logFile,
                                          Path masterLogFile,
                                          boolean echoMinimalToStderr) {
        if (STARTED.get()) return;
        Objects.requireNonNull(logFile, "logFile");

        List<Path> targets = new ArrayList<>();
        targets.add(logFile);
        if (masterLogFile != null && !pathsEqual(logFile, masterLogFile)) {
            targets.add(masterLogFile);
        }

        List<WriterTarget> openTargets = new ArrayList<>();

        try {
            for (Path target : targets) {
                Path normalized = normalizePath(target);
                Path parent = normalized.getParent();
                if (parent != null) Files.createDirectories(parent);
                BufferedWriter writer = Files.newBufferedWriter(normalized, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                openTargets.add(new WriterTarget(normalized, writer));
            }

            WRITERS = openTargets.toArray(WriterTarget[]::new);

            ECHO_MINIMAL = echoMinimalToStderr;

            ORIGINAL_OUT = System.out;
            ORIGINAL_ERR = System.err;

            // Capture System.out and System.err
            System.setOut(new PrintStream(new LineCaptureStream(Level.INFO), true, "UTF-8"));
            System.setErr(new PrintStream(new LineCaptureStream(Level.SEVERE), true, "UTF-8"));

            // Hook JUL (java.util.logging) to our file format
            installJulBridge();

            // Uncaught exceptions
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                logThrowable(Level.SEVERE, "Uncaught exception in thread \"" + t.getName() + "\"", e);
            });

            STARTED.set(true);
            if (ECHO_MINIMAL && ORIGINAL_ERR != null) {
                ORIGINAL_ERR.println("[program starting]");
            }

            // Ensure graceful close on exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (ECHO_MINIMAL && ORIGINAL_ERR != null) {
                        ORIGINAL_ERR.println("[program ended]");
                    }
                    stop();
                } catch (Exception ignored) { /* best-effort */ }
            }, "logger-shutdown"));

        } catch (IOException ioe) {
            // If we fail to initialize, fall back to original streams and report once.
            safePrintToOriginals("Logger initialization failed: " + ioe);
            restoreSystemStreams();
            for (WriterTarget target : openTargets) closeQuietly(target.writer);
            WRITERS = NO_WRITERS;
            throw new RuntimeException(ioe);
        }
    }

    /** Add an additional mirror target that will receive all log output. */
    public static synchronized void addMirror(Path extraFile) {
        Objects.requireNonNull(extraFile, "extraFile");
        if (!STARTED.get()) {
            throw new IllegalStateException("Logger has not been started");
        }

        Path normalized = normalizePath(extraFile);
        for (WriterTarget target : WRITERS) {
            if (pathsEqual(target.path, normalized)) {
                return; // already logging to this file
            }
        }

        try {
            Path parent = normalized.getParent();
            if (parent != null) Files.createDirectories(parent);
            BufferedWriter writer = Files.newBufferedWriter(normalized, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            WRITERS = append(WRITERS, new WriterTarget(normalized, writer));
        } catch (IOException ioe) {
            safePrintToOriginals("Logger addMirror failed: " + ioe);
        }
    }

    /** Stop logging and restore original System.out/err. */
    public static synchronized void stop() {
        if (!STARTED.get()) return;
        try {
            flushPending();
            restoreSystemStreams();
            uninstallJulBridge();
        } finally {
            WriterTarget[] writers = WRITERS;
            WRITERS = NO_WRITERS;
            for (WriterTarget target : writers) {
                closeQuietly(target.writer);
            }
            STARTED.set(false);
        }
    }

    /**
     * Attach a spawned process so its stdout/stderr are logged with levels INFO/SEVERE.
     * Example:
     *   Process p = new ProcessBuilder("ping", "-c", "1", "127.0.0.1").start();
     *   Logger.attachProcess("ping", p);
     */
    public static void attachProcess(String name, Process process) {
        Objects.requireNonNull(process, "process");
        startPumpThread(name, "stdout", process.getInputStream(), Level.INFO);
        startPumpThread(name, "stderr", process.getErrorStream(), Level.SEVERE);
    }

    /**
     * Suppress JavaFX internal chatter (FINE/FINER/FINES​T), but keep WARNING+.
     * Also keeps your app package "gauges" chatty in dev, quieter in prod.
     * Call this AFTER Logger.start(...).
     */
public static void quietJavaFX(boolean devMode) {
    // Keep your app chatty as you prefer
    setJulLoggerLevel("gauges", devMode ? Level.FINE : Level.INFO);

    // JavaFX noise → WARNING+
    setJulLoggerLevel("javafx", Level.WARNING);
    setJulLoggerLevel("javafx.scene", Level.WARNING);
    setJulLoggerLevel("javafx.css", Level.WARNING);
    setJulLoggerLevel("com.sun.javafx", Level.WARNING);

    // JDK internal noise → WARNING+
    setJulLoggerLevel("jdk", Level.WARNING);
    setJulLoggerLevel("jdk.event", Level.WARNING);
    setJulLoggerLevel("jdk.event.security", Level.WARNING);
    setJulLoggerLevel("sun.security", Level.WARNING);

    // Defensive filter on all root handlers
    if (JUL_ROOT != null) {
        for (Handler h : JUL_ROOT.getHandlers()) {
            h.setFilter(record -> {
                if (record == null) return false;
                String ln = record.getLoggerName();
                if (ln == null) ln = "";

                boolean isJavaFX = ln.startsWith("javafx") || ln.startsWith("com.sun.javafx");
                boolean isJdkInt  = ln.startsWith("jdk.") || ln.equals("jdk")
                                  || ln.startsWith("sun.security");

                // Drop low-level noise from JavaFX & JDK internals
                if ((isJavaFX || isJdkInt) &&
                    record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return false;
                }
                return true;
            });
        }
    }
}

    // --- Internals ----------------------------------------------------------

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final WriterTarget[] NO_WRITERS = new WriterTarget[0];
    private static volatile WriterTarget[] WRITERS = NO_WRITERS;
    private static volatile boolean ECHO_MINIMAL;

    private static volatile PrintStream ORIGINAL_OUT;
    private static volatile PrintStream ORIGINAL_ERR;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final StackWalker WALKER = StackWalker.getInstance(Set.of(
            StackWalker.Option.RETAIN_CLASS_REFERENCE,
            StackWalker.Option.SHOW_REFLECT_FRAMES));

    /** Captures lines written to System.out/err and logs them. */
    private static final class LineCaptureStream extends OutputStream {
        private final Level level;
        private final StringBuilder buf = new StringBuilder();

        LineCaptureStream(Level level) { this.level = level; }

        @Override public synchronized void write(int b) {
            buf.append((char) (b & 0xFF));
            drainLines(false);
        }

        @Override public synchronized void write(byte[] b, int off, int len) {
            if (len <= 0) return;
            buf.append(new String(b, off, len, StandardCharsets.UTF_8));
            drainLines(false);
        }

        @Override public synchronized void flush() {
            drainLines(true);
        }

        private void drainLines(boolean flushRemainder) {
            int idx;
            while ((idx = indexOfNewline(buf)) >= 0) {
                String line = buf.substring(0, idx);
                buf.delete(0, idx + 1); // remove line + newline
                logLine(level, line);
            }
            if (flushRemainder && buf.length() > 0) {
                String line = buf.toString();
                buf.setLength(0);
                logLine(level, line);
            }
        }

        private int indexOfNewline(StringBuilder sb) {
            for (int i = 0; i < sb.length(); i++) {
                char c = sb.charAt(i);
                if (c == '\n') return i;
            }
            return -1;
        }
    }

    /** JUL bridge handler that writes with the same format. */
    private static final class JulBridgeHandler extends Handler {
        @Override public void publish(LogRecord r) {
            if (r == null || !isLoggable(r)) return;
            String msg;
            try {
                msg = getFormatter() != null ? getFormatter().formatMessage(r) : r.getMessage();
            } catch (Exception e) {
                msg = r.getMessage();
            }
            StackTraceElement caller = fromLogRecord(r);
            writeFormatted(r.getLevel(), caller, msg);
            if (r.getThrown() != null) {
                logThrowable(r.getLevel(), "JUL Throwable", r.getThrown());
            }
        }
        @Override public void flush() { flushWriter(); }
        @Override public void close() { /* writer closed by Logger.stop() */ }
    }

    private static Handler JUL_HANDLER;
    private static java.util.logging.Logger JUL_ROOT;

private static void installJulBridge() {
    JUL_ROOT = java.util.logging.Logger.getLogger("");
    for (java.util.logging.Handler h : JUL_ROOT.getHandlers()) JUL_ROOT.removeHandler(h);

    JUL_HANDLER = new JulBridgeHandler();
    JUL_HANDLER.setLevel(java.util.logging.Level.ALL);
    JUL_HANDLER.setFormatter(new java.util.logging.SimpleFormatter());

    // Filter: drop JavaFX/JDK internals below WARNING
    JUL_HANDLER.setFilter(record -> {
        if (record == null) return false;
        String ln = record.getLoggerName();
        if (ln == null) ln = "";

        boolean isJavaFX = ln.startsWith("javafx") || ln.startsWith("com.sun.javafx");
        boolean isJdkInt  = ln.startsWith("jdk.") || ln.equals("jdk")
                          || ln.startsWith("sun.security");

        return !((isJavaFX || isJdkInt) &&
                 record.getLevel().intValue() < java.util.logging.Level.WARNING.intValue());
    });

    JUL_ROOT.addHandler(JUL_HANDLER);
    JUL_ROOT.setLevel(java.util.logging.Level.ALL);
}

    private static void uninstallJulBridge() {
        if (JUL_ROOT != null && JUL_HANDLER != null) {
            JUL_ROOT.removeHandler(JUL_HANDLER);
        }
        JUL_HANDLER = null;
        JUL_ROOT = null;
    }

    private static void startPumpThread(String name, String stream, InputStream in, Level lvl) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // Fake a caller as "process.<name>.<stream>"
                    StackTraceElement caller = new StackTraceElement("process." + name, stream, name, -1);
                    writeFormatted(lvl, caller, line);
                }
            } catch (IOException e) {
                logThrowable(Level.WARNING, "Process stream pump error (" + name + "/" + stream + ")", e);
            }
        }, "proc-" + name + "-" + stream);
        t.setDaemon(true);
        t.start();
    }

    private static void logLine(Level level, String msg) {
        StackTraceElement caller = findCaller();
        writeFormatted(level, caller, msg);
    }

    private static void logThrowable(Level level, String prefix, Throwable t) {
        if (t == null) return;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        StackTraceElement caller = findCaller();
        writeFormatted(level, caller, prefix);
        // Print stack trace lines with same header
        String[] lines = sw.toString().split("\\R");
        for (String ln : lines) {
            writeFormatted(level, caller, ln);
        }
    }

    private static void writeFormatted(Level level, StackTraceElement caller, String message) {
        WriterTarget[] writers = WRITERS;
        if (writers.length == 0) return;
        String ts = TS.format(LocalDateTime.now());
        String lvl = padLevel(mapLevelName(level));
        String loc = formatLocation(caller);
        String[] lines = message.split("\\R", -1); // keep empty lines
        synchronized (Logger.class) {
            try {
                for (String line : lines) {
                    for (WriterTarget target : writers) {
                        BufferedWriter writer = target.writer;
                        writer.write(ts);
                        writer.write(" | ");
                        writer.write(lvl);
                        writer.write(" | ");
                        writer.write(loc);
                        writer.write(" | ");
                        writer.write(line);
                        writer.write('\n');
                    }
                }
                for (WriterTarget target : writers) {
                    target.writer.flush();
                }
            } catch (IOException ioe) {
                safePrintToOriginals("Logger write failed: " + ioe);
            }
        }
    }

    private static void flushWriter() {
        synchronized (Logger.class) {
            WriterTarget[] writers = WRITERS;
            for (WriterTarget target : writers) {
                try { target.writer.flush(); } catch (IOException ignored) {}
            }
        }
    }

    private static void flushPending() {
        System.out.flush();
        System.err.flush();
        flushWriter();
    }

    private static void restoreSystemStreams() {
        if (ORIGINAL_OUT != null) System.setOut(ORIGINAL_OUT);
        if (ORIGINAL_ERR != null) System.setErr(ORIGINAL_ERR);
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) {}
    }

    private static WriterTarget[] append(WriterTarget[] existing, WriterTarget extra) {
        int len = existing.length;
        WriterTarget[] out = new WriterTarget[len + 1];
        System.arraycopy(existing, 0, out, 0, len);
        out[len] = extra;
        return out;
    }

    private static void safePrintToOriginals(String s) {
        try {
            if (ORIGINAL_ERR != null) ORIGINAL_ERR.println(s);
            else if (ORIGINAL_OUT != null) ORIGINAL_OUT.println(s);
            else System.err.println(s);
        } catch (Exception ignored) { /* swallow */ }
    }

    // --- Helpers: formatting & caller detection -----------------------------

    private static boolean pathsEqual(Path a, Path b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return normalizePath(a).equals(normalizePath(b));
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String padLevel(String s) {
        // Exactly 7 chars right-padded (e.g., "INFO   ", "WARNING", "SEVERE ")
        if (s.length() >= 7) return s.substring(0, 7);
        StringBuilder b = new StringBuilder(7);
        b.append(s);
        while (b.length() < 7) b.append(' ');
        return b.toString();
    }

    private static String mapLevelName(Level l) {
        if (l == null) return "INFO";
        int v = l.intValue();
        if (v >= Level.SEVERE.intValue()) return "SEVERE";
        if (v >= Level.WARNING.intValue()) return "WARNING";
        if (v >= Level.INFO.intValue()) return "INFO";
        if (v >= Level.CONFIG.intValue()) return "CONFIG";
        if (v >= Level.FINE.intValue()) return "FINE";
        if (v >= Level.FINER.intValue()) return "FINER";
        return "FINEST";
    }

    private static String formatLocation(StackTraceElement el) {
        if (el == null) return "?.?(?:-1)";
        String cls = el.getClassName();
        String method = el.getMethodName();
        String file = el.getFileName();
        int line = el.getLineNumber();
        if (file == null) file = "?";
        if (line < 0) line = -1;
        return cls + "." + method + "(" + file + ":" + line + ")";
    }

    private static StackTraceElement fromLogRecord(LogRecord r) {
        String cls = r.getSourceClassName();
        String mtd = r.getSourceMethodName();
        if (cls == null || mtd == null) {
            // best-effort from current stack to skip JUL frames
            return findCaller();
        }
        // File/line not provided in LogRecord; leave as unknown.
        return new StackTraceElement(cls, mtd, cls.substring(cls.lastIndexOf('.') + 1) + ".java", -1);
    }

    private static StackTraceElement findCaller() {
        StackTraceElement fallback = fallbackCaller();
        return WALKER.walk(stream -> stream
                .filter(frame -> {
                    String cn = frame.getClassName();
                    return !isLoggerFrame(cn) && !isInfrastructureFrame(cn);
                })
                .findFirst()
                .map(StackWalker.StackFrame::toStackTraceElement)
                .orElse(fallback));
    }

    private static StackTraceElement fallbackCaller() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        boolean seenLogger = false;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (!seenLogger) {
                if (isLoggerFrame(cn)) {
                    seenLogger = true;
                }
                continue;
            }
            if (isLoggerFrame(cn) || isInfrastructureFrame(cn)) {
                continue;
            }
            return e;
        }
        return st.length > 0 ? st[st.length - 1] : null;
    }

    private static boolean isLoggerFrame(String className) {
        if (className == null) return false;
        if (className.equals(Logger.class.getName())) return true;
        return className.startsWith(Logger.class.getName() + "$");
    }

    private static boolean isInfrastructureFrame(String className) {
        if (className == null) return false;
        return className.startsWith("java.io.PrintStream") ||
               className.startsWith("java.lang.Thread") ||
               className.startsWith("java.util.logging") ||
               className.startsWith("jdk.internal") ||
               className.startsWith("java.lang.reflect") ||
               className.startsWith("jdk.internal.reflect") ||
               className.startsWith("sun.reflect") ||
               className.startsWith("java.lang.invoke") ||
               className.startsWith("java.security.AccessController");
    }

    private static void setJulLoggerLevel(String name, Level level) {
        try {
            java.util.logging.Logger.getLogger(name).setLevel(level);
        } catch (Exception ignored) { /* best-effort */ }
    }

    private record WriterTarget(Path path, BufferedWriter writer) {}

    // --- Convenience methods for app code ----------------------------------

    public static void log(String msg) {
        logLine(Level.INFO, msg);
    }

    public static void info(String msg) {
        logLine(Level.INFO, msg);
    }

    public static void warn(String msg) {
        logLine(Level.WARNING, msg);
    }

    public static void error(String msg) {
        logLine(Level.SEVERE, msg);
    }


    private Logger() {}
}


