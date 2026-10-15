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
            if (value != null && (value.state() == ServerPerformance.State.VERIFIED || value.state() == ServerPerformance.State.FAILED))
                completed.put(key, value);
        });
    }
    public synchronized void record(Server server, ServerPerformance value) {
        Objects.requireNonNull(value);
        String key = PerformanceCache.serverKey(server);
        if (value.state() == ServerPerformance.State.TESTING) { testing.add(key); return; }
        testing.remove(key);
        if (value.state() == ServerPerformance.State.UNTESTED) completed.remove(key);
        else completed.put(key, value);
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
            return force || result == null || !result.fresh();
        }).toList();
    }
    public synchronized List<Server> ranked(List<Server> servers) {
        return servers.stream().filter(server -> {
            ServerPerformance result = completed.get(PerformanceCache.serverKey(server));
            return result != null && result.successful() && result.fresh();
        }).sorted(Comparator.comparingLong(server -> completed.get(PerformanceCache.serverKey(server)).millis())).toList();
    }
    public synchronized List<Server> quickCandidates(List<Server> servers) {
        return servers.stream().filter(server -> {
            ServerPerformance result = completed.get(PerformanceCache.serverKey(server));
            return result == null || result.state() != ServerPerformance.State.FAILED || !result.fresh();
        }).toList();
    }
    public synchronized boolean isEmpty() { return completed.isEmpty() && testing.isEmpty(); }
}
