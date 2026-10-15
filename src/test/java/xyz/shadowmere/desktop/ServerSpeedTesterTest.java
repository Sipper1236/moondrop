package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServerSpeedTesterTest {
    @Test void cancellationClosesTheEngineAndLeavesPendingResultsIncomplete() throws Exception {
        var probing = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var tester = new ServerSpeedTester(new ServerSpeedTester.Backend() {
            public boolean reachable(Server s) { return true; }
            public AutoCloseable start(Path b, Path d, Map<Integer,Server> bindings) { return closed::countDown; }
            public long probe(int port) throws Exception {
                probing.countDown(); new java.util.concurrent.CountDownLatch(1).await(); return 1;
            }
        });
        List<ServerSpeedTester.Update> updates = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread runner = new Thread(() -> {
            try { tester.test(List.of(server(0)), Path.of("fake"), Path.of("fake"), updates::add, ignored -> { }); }
            catch (InterruptedException expected) { cancelled.set(true); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        runner.start();
        assertTrue(probing.await(3, java.util.concurrent.TimeUnit.SECONDS));
        runner.interrupt(); runner.join(3000);
        assertFalse(runner.isAlive()); assertTrue(cancelled.get());
        assertEquals(0, closed.getCount());
        assertEquals(1, updates.size());
        assertEquals(ServerPerformance.State.TESTING, updates.get(0).performance().state());
    }
    private Server server(int n) { return new Server("" + n, "127.0.0.1", 8000 + n, "aes-256-gcm", "password", "", "", "", true); }
    @Test void testsEveryServerSkipsUnreachableAndClosesBatches() throws Exception {
        var opened = new AtomicInteger(); var closed = new AtomicInteger(); var probes = new AtomicInteger();
        var tester = new ServerSpeedTester(new ServerSpeedTester.Backend() {
            public boolean reachable(Server s) { return s.port() != 8002; }
            public AutoCloseable start(Path b, Path d, Map<Integer,Server> bindings) {
                assertTrue(bindings.size() <= 8); opened.incrementAndGet(); return () -> closed.incrementAndGet();
            }
            public long probe(int port) { probes.incrementAndGet(); return 42; }
        });
        var servers = java.util.stream.IntStream.range(0, 19).mapToObj(this::server).toList();
        List<ServerSpeedTester.Update> updates = new ArrayList<>();
        tester.test(servers, Path.of("fake"), Path.of("fake"), updates::add, ignored -> { });
        assertEquals(19, updates.stream().filter(u -> u.performance().state() == ServerPerformance.State.TESTING).count());
        assertEquals(18, updates.stream().filter(u -> u.performance().successful()).count());
        assertEquals(18, probes.get()); assertEquals(3, opened.get()); assertEquals(opened.get(), closed.get());
    }
    @Test void invalidBatchFallsBackAndDoesNotHideWorkingServers() throws Exception {
        var tester = new ServerSpeedTester(new ServerSpeedTester.Backend() {
            public boolean reachable(Server s) { return true; }
            public AutoCloseable start(Path b, Path d, Map<Integer,Server> bindings) throws Exception {
                if (bindings.values().stream().anyMatch(s -> s.port() == 8001)) throw new java.io.IOException("unsupported cipher");
                return () -> { };
            }
            public long probe(int port) { return 15; }
        });
        List<ServerSpeedTester.Update> updates = new ArrayList<>();
        tester.test(List.of(server(0), server(1), server(2)), Path.of("fake"), Path.of("fake"), updates::add, ignored -> { });
        assertEquals(2, updates.stream().filter(u -> u.performance().successful()).count());
        assertEquals(1, updates.stream().filter(u -> u.performance().state() == ServerPerformance.State.FAILED).count());
    }
}
