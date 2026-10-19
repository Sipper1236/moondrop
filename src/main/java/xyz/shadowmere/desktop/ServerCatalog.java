package xyz.shadowmere.desktop;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
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
    private final boolean defaultFeeds;

    public ServerCatalog() {this(defaultSources(feedClient(0)),Duration.ofSeconds(45),true);}
    static HttpClient feedClient(int proxyPort) {
        if(proxyPort<0||proxyPort>65535)throw new IllegalArgumentException("Invalid local proxy port");
        HttpClient.Builder builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if(proxyPort>0)builder.proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",proxyPort)));
        return builder.build();
    }
    private static List<Source> defaultSources(HttpClient client) {
        List<Source> feeds=new ArrayList<>();
        feeds.add(new Source("Shadowmere",()->new ShadowmereApi(client).fetch()));
        feeds.add(new Source("GFP Shadowsocks",()->ShadowsocksFeed.gfp(client).fetch()));
        feeds.add(new Source("V2rayCollector Shadowsocks",()->ShadowsocksFeed.collector(client).fetch()));
        feeds.add(new Source("Morpheus Shadowsocks",()->ShadowsocksFeed.morpheus(client).fetch()));
        for(String protocol:List.of("hy2","tuic")) {
            java.net.URI endpoint=java.net.URI.create("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/"+protocol+".txt");
            feeds.add(new Source("GFP "+protocol,()->ShadowsocksFeed.protectedFeed(endpoint,"gfp:",client).fetch()));
        }
        java.net.URI collector=java.net.URI.create("https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/mix");
        feeds.add(new Source("V2rayCollector protected",()->ShadowsocksFeed.protectedFeed(collector,"collector:",client).fetch()));
        for(String protocol:List.of("vless","trojan","hysteria2","tuic")) {
            java.net.URI endpoint=java.net.URI.create("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/"+protocol+".txt");
            feeds.add(new Source("Morpheus "+protocol,()->ShadowsocksFeed.protectedFeed(endpoint,"morpheus:",client).fetch()));
        }
        feeds.add(new Source("V2RayAggregator",()->ShadowsocksFeed.protectedFeed(java.net.URI.create("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"),"aggregator:",client).fetch()));
        feeds.add(new Source("Epodonios",()->ShadowsocksFeed.protectedFeed(java.net.URI.create("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"),"epodonios:",client).fetch()));
        return feeds;
    }
    ServerCatalog(List<Source> sources, Duration timeout) {
        this(sources,timeout,false);
    }
    private ServerCatalog(List<Source> sources,Duration timeout,boolean defaultFeeds) {
        this.sources=List.copyOf(sources);this.timeout=timeout;this.defaultFeeds=defaultFeeds;
    }
    /** Refresh through the connected engine; a failed proxy never falls back to a direct request. */
    public Result fetch(int localProxyPort) throws Exception {
        if(localProxyPort<1||localProxyPort>65535)throw new IllegalArgumentException("Invalid local proxy port");
        // Injected catalogs keep their deterministic sources, including when testing connected refreshes.
        return defaultFeeds ? new ServerCatalog(defaultSources(feedClient(localProxyPort)),timeout).fetch() : fetch();
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
                    if(entries.isEmpty()) {warnings.add(source.name()+" returned no compatible protected proxies.");continue;}
                    int added=0;
                    for(Server server:entries)if(unique.putIfAbsent(PerformanceCache.serverKey(server),server)==null)added++;
                    counts.put(source.name(),added);
                } catch(ExecutionException|CancellationException failure) {
                    // Feed URLs/credentials and third-party response bodies never enter user-facing errors.
                    warnings.add(source.name()+" is unavailable. Refresh to try again.");
                }
            }
            if(unique.isEmpty())throw new IOException("No server sources could provide compatible protected proxies. Check your connection and refresh.");
            List<Server> servers=unique.values().stream().sorted(Comparator.comparing(Server::countryName).thenComparing(Server::displayName)).toList();
            return new Result(servers,Collections.unmodifiableMap(counts),List.copyOf(warnings));
        } finally {workers.shutdownNow();}
    }
}
