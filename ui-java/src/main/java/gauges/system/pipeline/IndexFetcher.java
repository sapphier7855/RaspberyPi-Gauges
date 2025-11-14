package gauges.system.pipeline;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import gauges.system.Logger;

/**
 * IndexFetcher
 *
 * Polls a JSON index from a backend endpoint at a fixed period and forwards the body to a consumer.
 * Clean version with minimal logging.
 */
public final class IndexFetcher {

    private final URI endpoint;
    private final Duration period;
    private final Duration timeout;
    private final Consumer<String> onSnapshot;

    private final HttpClient client;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running;
    private volatile boolean stopping;
    private volatile ScheduledFuture<?> task;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile long tickCount = 0L;

    private static final PipelineDebugLog PIPELINE_LOG = PipelineDebugLog.shared();

    public IndexFetcher(URI endpoint, Duration period, Duration timeout, Consumer<String> onSnapshot) {
        this.endpoint   = Objects.requireNonNull(endpoint, "endpoint");
        this.period     = Objects.requireNonNull(period, "period");
        this.timeout    = Objects.requireNonNull(timeout, "timeout");
        this.onSnapshot = Objects.requireNonNull(onSnapshot, "onSnapshot");

        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IndexFetcher");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (running) return;
        stopping = false;
        running  = true;
        PIPELINE_LOG.info("[IndexFetcher] start endpoint=" + endpoint + " period=" + period + " timeout=" + timeout);
        task = scheduler.scheduleAtFixedRate(this::safeFetchOnce, 0L, period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running && !stopping) return;
        stopping = true;
        running  = false;
        PIPELINE_LOG.info("[IndexFetcher] stop requested (inFlight=" + inFlight.get() + ")");
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(Math.max(1, timeout.toMillis() / 1000), TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void safeFetchOnce() {
        if (!running) return;
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            fetchOnce();
        } catch (Throwable t) {
            // ignore unexpected exceptions quietly
            PIPELINE_LOG.error("[IndexFetcher] unexpected error", t);
        } finally {
            inFlight.set(false);
        }
    }

    private void fetchOnce() {
        if (!running) return;

        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json")
                .build();

        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            byte[] body = resp.body();
            int len = (body == null ? 0 : body.length);

            if (code >= 200 && code < 300 && len > 0) {
                String text = new String(body, StandardCharsets.UTF_8);
                long tick = ++tickCount;
                PIPELINE_LOG.info("[IndexFetcher][tick=" + tick + "][code=" + code + "][bytes=" + len + "] body=" + text);
                try {
                    onSnapshot.accept(text);
                } catch (Throwable ignored) { }
            } else {
                PIPELINE_LOG.warn("[IndexFetcher] response ignored code=" + code + " bytes=" + len);
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            PIPELINE_LOG.warn("[IndexFetcher] interrupted during fetch");
        } catch (IOException ioe) {
            PIPELINE_LOG.warn("[IndexFetcher] IO error " + ioe);
        } catch (Throwable t) {
            PIPELINE_LOG.error("[IndexFetcher] failure while fetching", t);
        }
    }

}

final class PipelineDebugLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");
    private static final StackWalker WALKER = StackWalker.getInstance(Set.of(
            StackWalker.Option.RETAIN_CLASS_REFERENCE,
            StackWalker.Option.SHOW_REFLECT_FRAMES));

    private static final String DEFAULT_FILE = "data-pipeline.log";
    private static final PipelineDebugLog SHARED = new PipelineDebugLog(DEFAULT_FILE);

    private final String fileName;

    private PipelineDebugLog(String fileName) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
    }

    static PipelineDebugLog forComponent(String fileName) {
        return shared();
    }

    static PipelineDebugLog shared() {
        return SHARED;
    }

    void info(String message) {
        log("INFO", message, null);
    }

    void warn(String message) {
        log("WARNING", message, null);
    }

    void error(String message, Throwable error) {
        log("SEVERE", message, error);
    }

    private void log(String level, String message, Throwable error) {
        if (!shouldLog()) {
            return;
        }
        if (message == null) {
            message = "";
        }
        StackTraceElement caller = findCaller();
        try {
            Path file = resolveFile();
            String ts = TS.format(LocalDateTime.now());
            String lvl = padLevel(level);
            String loc = formatLocation(caller);
            String[] lines = message.split("\\R", -1);
            synchronized (this) {
                Files.createDirectories(file.getParent());
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (String line : lines) {
                        writer.write(ts);
                        writer.write(" | ");
                        writer.write(lvl);
                        writer.write(" | ");
                        writer.write(loc);
                        writer.write(" | ");
                        writer.write(line);
                        writer.newLine();
                    }
                    if (error != null) {
                        StringWriter sw = new StringWriter();
                        error.printStackTrace(new PrintWriter(sw));
                        for (String line : sw.toString().split("\\R", -1)) {
                            writer.write(ts);
                            writer.write(" | ");
                            writer.write(lvl);
                            writer.write(" | ");
                            writer.write(loc);
                            writer.write(" | ");
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (IOException ioe) {
            fallback("[PipelineDebugLog] failed to write to " + fileName + ": " + ioe);
        }
    }

    private static boolean shouldLog() {
        return Logger.isEnabled();
    }

    private Path resolveFile() throws IOException {
        Path dir = resolveDirectory();
        return dir.resolve(fileName);
    }

    private static Path resolveDirectory() throws IOException {
        String base = System.getProperty("gauges.log.dataPipelineDir");
        Path dir;
        if (base != null) {
            String trimmed = base.trim();
            if (!trimmed.isEmpty()) {
                try {
                    dir = Paths.get(trimmed);
                } catch (Exception invalid) {
                    dir = Paths.get("logs", "data-pipline");
                }
            } else {
                dir = Paths.get("logs", "data-pipline");
            }
        } else {
            dir = Paths.get("logs", "data-pipline");
        }
        Files.createDirectories(dir);
        return dir;
    }

    private static String padLevel(String level) {
        if (level == null) {
            level = "INFO";
        }
        if (level.length() >= 7) {
            return level.substring(0, 7);
        }
        StringBuilder sb = new StringBuilder(level);
        while (sb.length() < 7) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String formatLocation(StackTraceElement caller) {
        if (caller == null) {
            return "?.?(?:-1)";
        }
        String cls = caller.getClassName();
        String method = caller.getMethodName();
        String file = caller.getFileName();
        int line = caller.getLineNumber();
        if (cls == null) cls = "?";
        if (method == null) method = "?";
        if (file == null) file = "?";
        if (line < 0) line = -1;
        return cls + "." + method + "(" + file + ":" + line + ")";
    }

    private static StackTraceElement findCaller() {
        return WALKER.walk(stream -> stream
                .filter(frame -> !isHelperFrame(frame.getClassName()))
                .findFirst()
                .map(StackWalker.StackFrame::toStackTraceElement)
                .orElse(null));
    }

    private static boolean isHelperFrame(String className) {
        if (className == null) {
            return false;
        }
        if (className.equals(PipelineDebugLog.class.getName())) {
            return true;
        }
        return className.startsWith(PipelineDebugLog.class.getName() + "$")
                || className.startsWith("java.io.")
                || className.startsWith("java.lang.reflect")
                || className.startsWith("jdk.internal")
                || className.startsWith("java.lang.invoke");
    }

    private static void fallback(String message) {
        try {
            Class<?> logger = Class.forName("gauges.system.Logger");
            var method = logger.getMethod("warn", String.class);
            method.invoke(null, message);
        } catch (Throwable t) {
            System.err.println(message);
        }
    }
}

