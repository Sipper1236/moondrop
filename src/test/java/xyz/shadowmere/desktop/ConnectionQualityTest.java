package xyz.shadowmere.desktop;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionQualityTest {
    @Test void countsFailuresAndUsesMedianOfSuccessfulProbes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var result = ConnectionQuality.measure(1, port -> {
            return switch (calls.incrementAndGet()) {
                case 1 -> 90;
                case 2 -> throw new IOException("unreachable");
                default -> 30;
            };
        });
        assertEquals(3, calls.get());
        assertEquals(2, result.successes()); assertEquals(3, result.attempts());
        assertEquals(60, result.millis());
    }
    @Test void allFailuresStillAttemptThreeRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var result = ConnectionQuality.measure(1, port -> { calls.incrementAndGet(); throw new IOException(); });
        assertEquals(3, calls.get()); assertFalse(result.successful());
    }
    @Test void cancellationStopsFurtherRequests() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> ConnectionQuality.measure(1, port -> {
            calls.incrementAndGet(); throw new InterruptedException();
        }));
        assertEquals(1, calls.get());
    }
    @Test void acceptsExactPayloadAndRejectsWrongSizesAndStatuses() throws Exception {
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            int size = Integer.parseInt(exchange.getRequestURI().getQuery());
            int status = size == -1 ? 302 : 200;
            byte[] payload = new byte[Math.max(1, size)];
            exchange.sendResponseHeaders(status, payload.length);
            try (var body = exchange.getResponseBody()) { body.write(payload); }
            catch (IOException expectedWhenCancelled) { }
        });
        fixture.start();
        try {
            var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/?";
            assertTrue(ConnectionQuality.download(client, URI.create(base + ConnectionQuality.DOWNLOAD_BYTES), Duration.ofSeconds(3)) > 0);
            for (int size : new int[]{0, 100, ConnectionQuality.DOWNLOAD_BYTES + 1, -1})
                assertThrows(IOException.class, () -> ConnectionQuality.download(client, URI.create(base + size), Duration.ofSeconds(3)));
        } finally { fixture.stop(0); }
    }
    @Test void totalDeadlineCoversWaitingForResponse() throws Exception {
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        fixture.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort() + "/");
            assertThrows(IOException.class, () -> ConnectionQuality.download(HttpClient.newHttpClient(), endpoint, Duration.ofMillis(50)));
        } finally { fixture.stop(0); }
    }
    @Test void interruptCancelsAnInFlightDownload() throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            received.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        fixture.start();
        Thread runner = new Thread(() -> {
            try {
                ConnectionQuality.download(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + fixture.getAddress().getPort() + "/"), Duration.ofSeconds(9));
            } catch (InterruptedException expected) { cancelled.set(true); }
            catch (IOException unexpected) { throw new AssertionError(unexpected); }
        });
        try {
            runner.start(); assertTrue(received.await(3, java.util.concurrent.TimeUnit.SECONDS));
            runner.interrupt(); runner.join(2000);
            assertFalse(runner.isAlive()); assertTrue(cancelled.get());
        } finally { runner.interrupt(); release.countDown(); fixture.stop(0); }
    }
}
