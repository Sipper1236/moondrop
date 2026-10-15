package xyz.shadowmere.desktop;

import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Ranks responsive endpoints, then compares actual HTTPS performance over Shadowsocks. */
public final class QuickConnect {
    public record Result(Server server, long millis, int tested) { }
    private record Candidate(Server server, long nanos) { }
    private record Verified(Server server, long millis) { }

    public Result find(List<Server> servers, Path binary, Path directory, Consumer<String> progress) throws Exception {
        return find(servers,binary,directory,progress,ignored -> { });
    }

    public Result find(List<Server> servers, Path binary, Path directory, Consumer<String> progress,
                       Consumer<ServerSpeedTester.Update> measurements) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Candidate>> reachable = new ArrayList<>();
        try {
            progress.accept("Checking " + servers.size() + " server addresses…");
            for (Server server : servers) reachable.add(pool.submit(() -> {
                long start = System.nanoTime();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(server.host(),server.port()), 1200);
                    return new Candidate(server, System.nanoTime() - start);
                } catch (IOException e) {if(!Thread.currentThread().isInterrupted())measurements.accept(new ServerSpeedTester.Update(server,ServerPerformance.failed())); return null; }
            }));
            List<Candidate> candidates = new ArrayList<>();
            for (Future<Candidate> future : reachable) { Candidate c = future.get(); if (c != null) candidates.add(c); }
            List<Server> shortlist = candidates.stream().sorted(Comparator.comparingLong(Candidate::nanos))
                    .limit(20).map(Candidate::server).toList();
            if (shortlist.isEmpty()) throw new IOException("No servers responded. Refresh the list or check your internet connection.");
            progress.accept("Testing HTTPS through " + shortlist.size() + " responsive servers…");
            // Each candidate has an isolated loopback listener and routing rule in one temporary core.
            Map<Integer,Server> bindings = new LinkedHashMap<>();
            List<ServerSocket> reservations = new ArrayList<>();
            try {
                for (Server server : shortlist) {
                    ServerSocket socket = new ServerSocket(); socket.bind(new InetSocketAddress("127.0.0.1", 0));
                    reservations.add(socket); bindings.put(socket.getLocalPort(),server);
                }
            } finally { for (ServerSocket socket : reservations) socket.close(); }
            try (ProxyEngine engine = new ProxyEngine(binary,directory)) {
                engine.start(bindings, ignored -> { });
                CompletionService<Verified> completed = new ExecutorCompletionService<>(pool);
                for (var binding : bindings.entrySet()) completed.submit(() -> {
                    try { return new Verified(binding.getValue(), ProxyEngine.probe(binding.getKey())); }
                    catch (IOException e) { return new Verified(binding.getValue(),-1); }
                });
                List<Verified> working = new ArrayList<>();
                for (int i = 0; i < bindings.size(); i++) {
                    Future<Verified> future = completed.poll(12,TimeUnit.SECONDS);
                    if (future == null) break;
                    Verified result = future.get();
                    if(result!=null) {
                        measurements.accept(new ServerSpeedTester.Update(result.server(),result.millis()>=0?ServerPerformance.verified(result.millis()):ServerPerformance.failed()));
                        if(result.millis()>=0)working.add(result);
                    }
                    progress.accept("HTTPS checks " + (i+1) + "/" + bindings.size() + " · " + working.size() + " working");
                }
                Verified best = working.stream().min(Comparator.comparingLong(Verified::millis))
                        .orElseThrow(() -> new IOException("The tested servers failed the HTTPS check. Refresh or try a server manually."));
                return new Result(best.server(), best.millis(), bindings.size());
            }
        } finally { pool.shutdownNow(); }
    }
}
