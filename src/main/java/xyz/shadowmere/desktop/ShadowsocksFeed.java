package xyz.shadowmere.desktop;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Imports encrypted subscriptions; protected transports can be enabled for additional feeds. */
public final class ShadowsocksFeed {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    interface Transport { InputStream open() throws Exception; }
    private final Transport transport;
    private final Duration timeout;
    private final String prefix;
    private final boolean protectedProtocols;

    public static ShadowsocksFeed gfp() {
        return new ShadowsocksFeed(URI.create("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt"), "gfp:");
    }
    public static ShadowsocksFeed collector() {
        return new ShadowsocksFeed(URI.create("https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/ss"), "collector:");
    }
    public static ShadowsocksFeed morpheus() {
        return new ShadowsocksFeed(URI.create("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/shadowsocks.txt"), "morpheus:");
    }
    public static ShadowsocksFeed protectedFeed(URI endpoint,String prefix) {return new ShadowsocksFeed(endpoint,prefix,true);}
    private ShadowsocksFeed(URI endpoint,String prefix) {this(endpoint,prefix,false);}
    private ShadowsocksFeed(URI endpoint, String prefix, boolean protectedProtocols) {
        this.prefix=prefix;this.protectedProtocols=protectedProtocols;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        transport = () -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Moondrop").header("Accept", "text/plain").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Proxy feed returned HTTP " + response.statusCode() + ". Try refreshing later.");
            }
            return response.body();
        };
        timeout = Duration.ofSeconds(25);
    }
    ShadowsocksFeed(Transport transport, Duration timeout) { this.transport = transport; this.timeout = timeout; this.prefix="gfp:";this.protectedProtocols=false; }

    public List<Server> fetch() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "shadowsocks-feed-fetch"); thread.setDaemon(true); return thread;
        });
        AtomicReference<InputStream> active = new AtomicReference<>();
        Future<List<Server>> future = reader.submit(() -> {
            try (InputStream input = transport.open()) {
                active.set(input);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Proxy feed exceeded the 8 MiB download limit.");
                List<Server> servers = parseReport(new String(bytes, StandardCharsets.UTF_8),prefix,protectedProtocols).servers();
                if (servers.isEmpty()) throw new IOException("Proxy feed returned no compatible protected proxies. Try refreshing later.");
                return servers;
            }
        });
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) { throw new IOException("Proxy feed took too long to respond. Try refreshing later."); }
        catch (InterruptedException cancellation) { Thread.currentThread().interrupt(); throw cancellation; }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof InterruptedException cancellation) { Thread.currentThread().interrupt(); throw cancellation; }
            if (cause instanceof IOException io && io.getMessage() != null && io.getMessage().startsWith("Proxy feed")) throw io;
            throw new IOException("Could not load the Proxy feed. Check your connection and try refreshing later.");
        } finally {
            future.cancel(true);
            InputStream input = active.get();
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            reader.shutdownNow();
        }
    }

    record ParseReport(List<Server> servers, int malformed, int unsupported, int plugins, int duplicates) { }
    static List<Server> parse(String text) throws IOException { return parseReport(text).servers(); }
    static ParseReport parseReport(String text) throws IOException {return parseReport(text,"gfp:");}
    static ParseReport parseReport(String text,String prefix) throws IOException {return parseReport(text,prefix,false);}
    static ParseReport parseProtectedReport(String text,String prefix) throws IOException {return parseReport(text,prefix,true);}
    private static ParseReport parseReport(String text,String prefix,boolean protectedProtocols) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Proxy feed exceeded the 8 MiB download limit.");
        Map<String,Server> unique = new LinkedHashMap<>();
        int malformed = 0, unsupported = 0, plugins = 0, duplicates = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (!protectedProtocols&&!line.startsWith("ss://")) { malformed++; continue; }
            try {
                int fragmentAt = line.indexOf('#');
                String connection = fragmentAt < 0 ? line : line.substring(0, fragmentAt);
                int queryAt = connection.indexOf('?');
                if (queryAt >= 0) {
                    String query = URLDecoder.decode(connection.substring(queryAt + 1), StandardCharsets.UTF_8);
                    if (Pattern.compile("(?i)(?:^|&)plugin(?:=|$)").matcher(query).find()) { plugins++; continue; }
                }
                Server parsed = protectedProtocols?ProxyLinks.parse("",connection,"","","",true):Server.parse("", connection, "", "", "", true);
                if (parsed.protocol().equals("shadowsocks")&&!Server.supportsEncryptedMethod(parsed.method())) { unsupported++; continue; }
                if (parsed.protocol().equals("shadowsocks")&&!validKey(parsed)) { malformed++; continue; }
                String key = PerformanceCache.serverKey(parsed);
                if (unique.containsKey(key)) { duplicates++; continue; }
                String label = fragmentAt < 0 ? "" : URLDecoder.decode(line.substring(fragmentAt + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
                String country = flagCountry(label);
                label = sanitize(label, parsed.password());
                unique.put(key, new Server(prefix + key, parsed.host(), parsed.port(), parsed.method(), parsed.password(), country, label, "", true, parsed.protocol(), parsed.profileJson()));
            } catch (RuntimeException invalid) { malformed++; }
        }
        return new ParseReport(List.copyOf(unique.values()), malformed, unsupported, plugins, duplicates);
    }
    private static boolean validKey(Server server) {
        if (!server.method().startsWith("2022-")) return true;
        int length = server.method().equals("2022-blake3-aes-128-gcm") ? 16 : 32;
        try {
            for (String key : server.password().split(":", -1)) if (Base64.getDecoder().decode(key).length != length) return false;
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String sanitize(String label, String password) {
        // Fragments are third-party presentation text, never trusted markup or credentials.
        if (label.contains(password)) return "";
        label = URL.matcher(label).replaceAll("");
        StringBuilder clean = new StringBuilder();
        label.codePoints().filter(code -> !Character.isISOControl(code) && Character.getType(code) != Character.FORMAT
            && code != '<' && code != '>').limit(96).forEach(clean::appendCodePoint);
        return clean.toString().strip();
    }
    private static String flagCountry(String label) {
        int[] points = label.codePoints().toArray();
        for (int i = 0; i + 1 < points.length; i++) {
            int first = points[i] - 0x1F1E6, second = points[i + 1] - 0x1F1E6;
            if (first >= 0 && first < 26 && second >= 0 && second < 26) {
                String country = "" + (char) ('A' + first) + (char) ('A' + second);
                if (COUNTRIES.contains(country)) return country;
            }
        }
        return "";
    }
}
