package xyz.shadowmere.desktop;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Small, bounded end-to-end measurements through an existing local proxy. */
public final class ConnectionQuality {
    static final int DOWNLOAD_BYTES = 1_048_576;
    private ConnectionQuality() { }
    @FunctionalInterface interface Probe { long probe(int port) throws Exception; }

    public static ServerPerformance measure(int port) throws Exception {
        return measure(port, ProxyEngine::probe);
    }
    static ServerPerformance measure(int port, Probe probe) throws Exception {
        List<Long> successes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            interrupted();
            try { successes.add(probe.probe(port)); }
            catch (InterruptedException cancellation) { throw cancellation; }
            catch (IOException failure) { /* Count the failed request and continue. */ }
        }
        interrupted();
        return ServerPerformance.measured(successes, 3);
    }

    public static long download(int port) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        return download(client, URI.create("https://speed.cloudflare.com/__down?bytes=1048576"), Duration.ofSeconds(9));
    }

    static long download(HttpClient client, URI endpoint, Duration timeout) throws IOException, InterruptedException {
        interrupted();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Accept-Encoding", "identity").GET().build();
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<Long>> pending = client.sendAsync(request, info -> new LimitedBody(info.statusCode()));
        try {
            HttpResponse<Long> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            interrupted();
            if (response.statusCode() != 200 || response.body() != DOWNLOAD_BYTES)
                throw new IOException("The download measurement returned an unexpected response.");
            return Math.max(1, (long) (DOWNLOAD_BYTES * 1_000_000_000.0 / Math.max(1, System.nanoTime() - started)));
        } catch (TimeoutException failure) {
            throw new IOException("The download measurement timed out.", failure);
        } catch (ExecutionException failure) {
            throw new IOException("The download measurement failed.", failure.getCause());
        } finally { pending.cancel(true); }
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Connection measurement cancelled");
    }

    /** Discards bytes as they arrive, retaining no response payload in memory. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<Long> {
        private final CompletableFuture<Long> result = new CompletableFuture<>();
        private final int status;
        private Flow.Subscription subscription;
        private long bytes;
        LimitedBody(int status) { this.status = status; }
        public CompletionStage<Long> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (status != 200) { subscription.cancel(); result.completeExceptionally(new IOException("Unexpected HTTP status " + status)); }
            else subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                bytes += buffer.remaining();
                if (bytes > DOWNLOAD_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Download exceeded the measurement limit.")); return;
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() {
            if (bytes == DOWNLOAD_BYTES) result.complete(bytes);
            else result.completeExceptionally(new IOException("Download was empty or truncated."));
        }
    }
}
