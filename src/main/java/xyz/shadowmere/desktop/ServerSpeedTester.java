package xyz.shadowmere.desktop;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Measures every supplied server in bounded batches without changing the system proxy. */
public final class ServerSpeedTester {
    public record Update(Server server, ServerPerformance performance) { }
    interface Backend {
        boolean reachable(Server server) throws Exception;
        AutoCloseable start(Path binary, Path directory, Map<Integer,Server> bindings) throws Exception;
        long probe(int port) throws Exception;
    }
    private final Backend backend;
    public ServerSpeedTester() { this(new Backend() {
        public boolean reachable(Server server) throws Exception {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server.host(), server.port()), 1200); return true;
            }
        }
        public AutoCloseable start(Path binary, Path directory, Map<Integer,Server> bindings) throws Exception {
            ProxyEngine engine = new ProxyEngine(binary, directory);
            try { engine.start(bindings, ignored -> { }); return engine; }
            catch (Exception failure) { engine.close(); throw failure; }
        }
        public long probe(int port) throws Exception { return ProxyEngine.probe(port); }
    }); }
    ServerSpeedTester(Backend backend) { this.backend = backend; }

    public void test(List<Server> servers, Path binary, Path directory,
                     Consumer<Update> results, Consumer<String> progress) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8, work -> {
            Thread thread = new Thread(work, "server-speed-test"); thread.setDaemon(true); return thread;
        });
        try {
            for (int offset = 0; offset < servers.size(); offset += 8) {
                interrupted();
                List<Server> batch = servers.subList(offset, Math.min(offset + 8, servers.size()));
                progress.accept("Testing servers " + (offset + 1) + "–" + (offset + batch.size()) + " of " + servers.size());
                List<Future<Boolean>> checks = new ArrayList<>();
                for (Server server : batch) {
                    results.accept(new Update(server, ServerPerformance.testing()));
                    checks.add(workers.submit(() -> {
                        try { return backend.reachable(server); }
                        catch (InterruptedException cancellation) { throw cancellation; }
                        catch (Exception failure) { return false; }
                    }));
                }
                List<Server> reachable = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    if (get(checks.get(i))) reachable.add(batch.get(i));
                    else results.accept(new Update(batch.get(i), ServerPerformance.failed()));
                }
                if (!reachable.isEmpty()) measure(reachable, binary, directory, workers, results);
            }
        } finally { workers.shutdownNow(); }
    }

    private void measure(List<Server> servers, Path binary, Path directory, ExecutorService workers,
                         Consumer<Update> results) throws Exception {
        interrupted();
        Map<Integer,Server> bindings = new LinkedHashMap<>();
        List<ServerSocket> reservations = new ArrayList<>();
        try {
            for (Server server : servers) {
                ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                reservations.add(socket); bindings.put(socket.getLocalPort(), server);
            }
        } finally { for (ServerSocket socket : reservations) socket.close(); }
        AutoCloseable engine;
        try { engine = backend.start(binary, directory, bindings); }
        catch (InterruptedException cancellation) { throw cancellation; }
        catch (Exception failure) {
            interrupted();
            // A single unsupported cipher must not prevent its neighbours from being measured.
            if (servers.size() > 1) {
                for (Server server : servers) measure(List.of(server), binary, directory, workers, results);
            } else results.accept(new Update(servers.get(0), ServerPerformance.failed()));
            return;
        }
        try (engine) {
            List<Future<ServerPerformance>> probes = new ArrayList<>();
            for (int port : bindings.keySet()) probes.add(workers.submit(() -> {
                try { return ServerPerformance.verified(backend.probe(port)); }
                catch (InterruptedException cancellation) { throw cancellation; }
                catch (Exception failure) { return ServerPerformance.failed(); }
            }));
            int i = 0;
            for (Server server : bindings.values()) {
                interrupted(); results.accept(new Update(server, get(probes.get(i++))));
            }
        }
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Server measurement cancelled");
    }
    private static <T> T get(Future<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException(failure.getCause());
        }
    }
}
