package gauges.system.pipeline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
        task = scheduler.scheduleAtFixedRate(this::safeFetchOnce, 0L, period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running && !stopping) return;
        stopping = true;
        running  = false;
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
        } finally {
            inFlight.set(false);
        }
    }

    private void fetchOnce() {
        if (!running) return;
        long seq = ++tickCount;

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
                try {
                    onSnapshot.accept(text);
                } catch (Throwable ignored) { }
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } catch (Throwable ignored) {
        }
    }
}

