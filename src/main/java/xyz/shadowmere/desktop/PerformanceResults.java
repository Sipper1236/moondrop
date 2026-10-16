package xyz.shadowmere.desktop;

import java.util.*;

/** Separates durable completed results from the presentation state of in-flight checks. */
public final class PerformanceResults {
    private final Map<String,ServerPerformance> completed = new HashMap<>();
    private final Set<String> testing = new HashSet<>();

    public synchronized ServerPerformance get(Server server) {
        String key = PerformanceCache.serverKey(server);
        return testing.contains(key) ? ServerPerformance.testing() : completed.get(key);
    }
    public synchronized void replaceAll(Map<String,ServerPerformance> values) {
        completed.clear(); testing.clear();
        values.forEach((key, value) -> {
            if (value != null && value.completed())
                completed.put(key, value);
        });
    }
    public synchronized void record(Server server, ServerPerformance value) {
        Objects.requireNonNull(value);
        String key = PerformanceCache.serverKey(server);
        if (value.state() == ServerPerformance.State.TESTING) { testing.add(key); return; }
        testing.remove(key);
        if (value.state() == ServerPerformance.State.UNTESTED) completed.remove(key);
        else if(value.completed())completed.put(key, value);
    }
    public synchronized Map<Server,ServerPerformance> cancelTesting(List<Server> servers) {
        Map<Server,ServerPerformance> changed = new LinkedHashMap<>();
        for (Server server : servers) {
            String key = PerformanceCache.serverKey(server);
            if (testing.contains(key)) changed.put(server, completed.getOrDefault(key, ServerPerformance.untested()));
        }
        for (Server server : changed.keySet()) testing.remove(PerformanceCache.serverKey(server));
        return changed;
    }
    public synchronized Map<String,ServerPerformance> snapshot() { return new HashMap<>(completed); }
    public synchronized List<Server> pending(List<Server> servers, boolean force) {
        return servers.stream().filter(server -> {
            ServerPerformance result = completed.get(PerformanceCache.serverKey(server));
            return force || result == null || !result.fresh() || result.successful()&&!result.hasQuality();
        }).toList();
    }
    public synchronized List<Server> ranked(List<Server> servers) {
        return servers.stream().filter(server -> {
            ServerPerformance result = completed.get(PerformanceCache.serverKey(server));
            return result != null && result.successful() && result.completed();
        }).sorted(Comparator.comparing(server -> completed.get(PerformanceCache.serverKey(server)),ServerPerformance.bestFirst())).toList();
    }
    /** Working results can be used immediately; a download test is not required to connect. */
    public synchronized List<Server> connectionCandidates(List<Server> servers,int limit) {
        if(limit<0)throw new IllegalArgumentException("Negative candidate limit");
        return ranked(servers).stream().filter(server -> completed.get(PerformanceCache.serverKey(server)).fresh())
                .limit(limit).toList();
    }
    public synchronized List<Server> bandwidthCandidates(List<Server> servers,int limit){
        if(limit<0)throw new IllegalArgumentException("Negative candidate limit");
        // Choose a stable shortlist before checking its bandwidth cache. Otherwise every launch
        // advances to another batch of unmeasured servers and eventually downloads through them all.
        Comparator<ServerPerformance> shortlist=Comparator.comparingDouble(ServerPerformance::reliability).reversed()
                .thenComparingLong(ServerPerformance::millis);
        return servers.stream().filter(server -> {
            ServerPerformance result=completed.get(PerformanceCache.serverKey(server));
            return result!=null&&result.successful()&&result.hasQuality()&&result.fresh();
        }).sorted(Comparator.comparing(server -> completed.get(PerformanceCache.serverKey(server)),shortlist))
                .limit(limit).filter(server -> !completed.get(PerformanceCache.serverKey(server)).throughputFresh()).toList();
    }
    public synchronized List<Server> quickCandidates(List<Server> servers) {
        return servers.stream().filter(server -> {
            ServerPerformance result = completed.get(PerformanceCache.serverKey(server));
            return result == null || result.state() != ServerPerformance.State.FAILED || !result.fresh();
        }).toList();
    }
    public synchronized boolean isEmpty() { return completed.isEmpty() && testing.isEmpty(); }
}
