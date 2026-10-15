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
    private record Verified(Server server, ServerPerformance quality) { }

    public Result find(List<Server> servers, Path binary, Path directory, Consumer<String> progress) throws Exception {
        return find(servers,binary,directory,progress,ignored -> { });
    }

    public Result find(List<Server> servers, Path binary, Path directory, Consumer<String> progress,
                       Consumer<ServerSpeedTester.Update> measurements) throws Exception {
        return find(servers,binary,directory,progress,measurements,null);
    }
    public Result find(List<Server> servers, Path binary, Path directory, Consumer<String> progress,
                       Consumer<ServerSpeedTester.Update> measurements, String nearbyCountry) throws Exception {
        return find(servers,binary,directory,progress,measurements,nearbyCountry,Map.of());
    }
    public Result find(List<Server> servers,Path binary,Path directory,Consumer<String> progress,
                       Consumer<ServerSpeedTester.Update> measurements,String nearbyCountry,Map<String,ServerPerformance> cached) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Server> selected = selectCandidates(servers, 256, new Random(), nearbyCountry);
            progress.accept("Checking " + selected.size() + " of " + servers.size() + " server addresses…");
            List<Server> shortlist;
            Set<Socket> sockets = ConcurrentHashMap.newKeySet();
            try {
                shortlist = discover(selected, TimeUnit.SECONDS.toNanos(8), server -> {
                    // QUIC candidates have no TCP preflight; their real HTTPS path is verified below.
                    if(server.usesUdpTransport())return 0;
                    long start = System.nanoTime();
                    Socket socket = new Socket();
                    sockets.add(socket);
                    try (socket) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        socket.connect(new InetSocketAddress(server.host(), server.port()), 1200);
                        return System.nanoTime() - start;
                    } finally { sockets.remove(socket); }
                }, measurements, nearbyCountry);
            } finally {
                for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            }
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
                    try {
                        ServerPerformance quality=ConnectionQuality.measure(binding.getKey());
                        ServerPerformance previous=cached.get(PerformanceCache.serverKey(binding.getValue()));
                        if(quality.successful()&&previous!=null&&previous.throughputFresh())
                            quality=new ServerPerformance(quality.state(),quality.millis(),quality.checkedAt(),quality.successes(),quality.attempts(),previous.bytesPerSecond(),previous.throughputAt());
                        return new Verified(binding.getValue(),quality);
                    } catch(InterruptedException cancellation){throw cancellation;}
                    catch (Exception e) { return new Verified(binding.getValue(),ServerPerformance.failed()); }
                });
                List<Verified> working = new ArrayList<>();
                for (int i = 0; i < bindings.size(); i++) {
                    Future<Verified> future = completed.poll(20,TimeUnit.SECONDS);
                    if (future == null) break;
                    Verified result = future.get();
                    if(result!=null) {
                        measurements.accept(new ServerSpeedTester.Update(result.server(),result.quality()));
                        if(result.quality().successful())working.add(result);
                    }
                    progress.accept("HTTPS checks " + (i+1) + "/" + bindings.size() + " · " + working.size() + " working");
                }
                List<Verified> downloadTargets=working.stream().sorted(Comparator
                        .comparingDouble((Verified value)->value.quality().reliability()).reversed()
                        .thenComparingLong(value->value.quality().millis())).limit(5).toList();
                int downloads=0;
                for(Verified target:downloadTargets) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(target.quality().throughputFresh())continue;
                    progress.accept("Measuring download speed "+(++downloads)+" of up to 5…");
                    int port=bindings.entrySet().stream().filter(entry->entry.getValue().equals(target.server())).findFirst().orElseThrow().getKey();
                    long rate;
                    try {rate=ConnectionQuality.download(port);}
                    catch(InterruptedException cancellation){throw cancellation;}
                    catch(IOException unavailable){rate=0;}
                    Verified updated=new Verified(target.server(),target.quality().withThroughput(rate));
                    working.set(working.indexOf(target),updated);
                    measurements.accept(new ServerSpeedTester.Update(updated.server(),updated.quality()));
                }
                Verified best = working.stream().min(Comparator.comparing(Verified::quality,ServerPerformance.bestFirst()))
                        .orElseThrow(() -> new IOException("The tested servers failed the HTTPS check. Refresh or try a server manually."));
                return new Result(best.server(), best.quality().millis(), bindings.size());
            }
        } finally { pool.shutdownNow(); }
    }
    @FunctionalInterface
    interface TcpProbe { long probe(Server server) throws Exception; }

    /** Balance sources first, then countries, so a large feed cannot bury a smaller one. */
    static List<Server> selectCandidates(List<Server> servers, int limit, Random random) {
        if (servers.size() <= limit) return List.copyOf(servers);
        Map<String, Map<String, List<Server>>> sources = new LinkedHashMap<>();
        for (Server server : servers) {
            String source = server.sourceName()+":"+server.protocol();
            sources.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(server.country(), ignored -> new ArrayList<>()).add(server);
        }
        List<Deque<Deque<Server>>> groups = new ArrayList<>();
        for (var countries : sources.values()) {
            List<Deque<Server>> buckets = new ArrayList<>();
            for (var country : countries.values()) {
                Collections.shuffle(country, random);
                buckets.add(new ArrayDeque<>(country));
            }
            Collections.shuffle(buckets, random);
            groups.add(new ArrayDeque<>(buckets));
        }
        Collections.shuffle(groups, random);
        List<Server> selected = new ArrayList<>();
        while (selected.size() < limit && groups.stream().anyMatch(g -> !g.isEmpty())) {
            for (var countries : groups) {
                if (countries.isEmpty() || selected.size() == limit) continue;
                var bucket = countries.removeFirst();
                selected.add(bucket.removeFirst());
                if (!bucket.isEmpty()) countries.addLast(bucket);
            }
        }
        return selected;
    }

    /** Within each source, try approximate nearby countries before more distant locations. */
    static List<Server> selectCandidates(List<Server> servers,int limit,Random random,String nearbyCountry) {
        if(nearbyCountry==null)return selectCandidates(servers,limit,random);
        List<Server> shuffled=new ArrayList<>(servers);Collections.shuffle(shuffled,random);
        List<Server> ordered=ServerProximity.prioritize(shuffled,nearbyCountry);
        if(ordered.size()<=limit)return ordered;
        Map<String,Deque<Server>> sources=new LinkedHashMap<>();
        for(Server server:ordered)sources.computeIfAbsent(server.sourceName()+":"+server.protocol(),ignored -> new ArrayDeque<>()).addLast(server);
        List<Server> selected=new ArrayList<>();
        while(selected.size()<limit&&sources.values().stream().anyMatch(group -> !group.isEmpty())) {
            for(var group:sources.values())if(!group.isEmpty()&&selected.size()<limit)selected.add(group.removeFirst());
        }
        return ServerProximity.prioritize(selected,nearbyCountry);
    }

    /** Only completed probes publish failures; queued or cancelled work stays unmeasured. */
    static List<Server> discover(List<Server> servers, long budgetNanos, TcpProbe probe,
                                 Consumer<ServerSpeedTester.Update> measurements) throws Exception {
        return discover(servers,budgetNanos,probe,measurements,null);
    }
    static List<Server> discover(List<Server> servers,long budgetNanos,TcpProbe probe,
                                 Consumer<ServerSpeedTester.Update> measurements,String nearbyCountry) throws Exception {
        ExecutorService discovery = Executors.newFixedThreadPool(16);
        List<Future<Candidate>> pending = new ArrayList<>();
        CompletionService<Candidate> completed = new ExecutorCompletionService<>(discovery);
        long deadline = System.nanoTime() + budgetNanos;
        try {
            for (Server server : servers) pending.add(completed.submit(() -> {
                try { return new Candidate(server, probe.probe(server)); }
                catch (IOException failure) { return new Candidate(server, -1); }
            }));
            List<Candidate> responsive = new ArrayList<>();
            for (int i = 0; i < servers.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<Candidate> future = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                Candidate candidate = future.get();
                if (candidate.nanos() >= 0) responsive.add(candidate);
                else measurements.accept(new ServerSpeedTester.Update(candidate.server(), ServerPerformance.failed()));
            }
            // Keep each source represented in HTTPS checks as well as the initial sample.
            // A large feed of fast TCP responders must not crowd out working encrypted paths.
            Map<String,Deque<Server>> sources=new LinkedHashMap<>();
            List<Server> tcpOrder=responsive.stream().sorted(Comparator.comparingLong(Candidate::nanos)).map(Candidate::server).toList();
            for(Server server:ServerProximity.prioritize(tcpOrder,nearbyCountry))
                sources.computeIfAbsent(server.sourceName()+":"+server.protocol(),ignored -> new ArrayDeque<>()).addLast(server);
            List<Server> shortlist=new ArrayList<>();
            while(shortlist.size()<20&&sources.values().stream().anyMatch(group -> !group.isEmpty())) {
                for(var group:sources.values())if(!group.isEmpty()&&shortlist.size()<20)shortlist.add(group.removeFirst());
            }
            return ServerProximity.prioritize(shortlist,nearbyCountry);
        } finally {
            for (Future<Candidate> future : pending) future.cancel(true);
            discovery.shutdownNow();
        }
    }

}
