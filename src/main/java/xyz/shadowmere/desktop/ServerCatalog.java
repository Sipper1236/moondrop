package xyz.shadowmere.desktop;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Independent feeds share one connection-identity namespace and one loading deadline. */
public final class ServerCatalog {
    @FunctionalInterface interface Fetch { List<Server> get() throws Exception; }
    record Source(String name, Fetch fetch) { }
    public record Result(List<Server> servers, Map<String,Integer> counts, List<String> warnings) { }
    private final List<Source> sources;
    private final Duration timeout;

    public ServerCatalog() {
        this(List.of(new Source("Shadowmere", () -> new ShadowmereApi().fetch()),
                new Source("GFP", () -> ShadowsocksFeed.gfp().fetch()),
                new Source("V2rayCollector", () -> ShadowsocksFeed.collector().fetch()),
                new Source("Morpheus", () -> ShadowsocksFeed.morpheus().fetch())), Duration.ofSeconds(45));
    }
    ServerCatalog(List<Source> sources, Duration timeout) {
        this.sources=List.copyOf(sources);this.timeout=timeout;
    }
    public Result fetch() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(Math.max(1,sources.size()),work -> {
            Thread thread=new Thread(work,"server-feed");thread.setDaemon(true);return thread;
        });
        try {
            List<Callable<List<Server>>> tasks=sources.stream().<Callable<List<Server>>>map(source -> source.fetch()::get).toList();
            List<Future<List<Server>>> results=workers.invokeAll(tasks,timeout.toMillis(),TimeUnit.MILLISECONDS);
            Map<String,Server> unique=new LinkedHashMap<>();Map<String,Integer> counts=new LinkedHashMap<>();
            List<String> warnings=new ArrayList<>();
            for(int i=0;i<sources.size();i++) {
                Source source=sources.get(i);Future<List<Server>> future=results.get(i);
                try {
                    List<Server> entries=future.get();
                    if(entries.isEmpty()) {warnings.add(source.name()+" returned no compatible Shadowsocks servers.");continue;}
                    int added=0;
                    for(Server server:entries)if(unique.putIfAbsent(PerformanceCache.serverKey(server),server)==null)added++;
                    counts.put(source.name(),added);
                } catch(ExecutionException|CancellationException failure) {
                    // Feed URLs/credentials and third-party response bodies never enter user-facing errors.
                    warnings.add(source.name()+" is unavailable. Refresh to try again.");
                }
            }
            if(unique.isEmpty())throw new IOException("No server sources could provide compatible Shadowsocks servers. Check your connection and refresh.");
            List<Server> servers=unique.values().stream().sorted(Comparator.comparing(Server::countryName).thenComparing(Server::displayName)).toList();
            return new Result(servers,Collections.unmodifiableMap(counts),List.copyOf(warnings));
        } finally {workers.shutdownNow();}
    }
}
