package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Approximate country-level proximity; never stores an IP address or precise user location. */
public final class ServerProximity {
    private static final int MAX_BODY = 65_536;
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Map<String, double[]> CENTROIDS = loadCentroids();
    @FunctionalInterface interface Transport { byte[] fetch() throws Exception; }
    private final Path file;
    private final Transport transport;
    private final Clock clock;
    private boolean attempted;
    private Optional<String> located = Optional.empty();

    public ServerProximity(Path data) { this(data, ServerProximity::fetchCountry, Clock.systemUTC()); }
    ServerProximity(Path data, Transport transport, Clock clock) {
        this.file = data.resolve("location-country.json"); this.transport = transport; this.clock = clock;
    }

    /** One lookup per instance; valid overrides and fresh country caches require no request. */
    public synchronized Optional<String> locate() {
        String override = normalize(System.getProperty("moondrop.country"));
        if (override != null) return Optional.of(override);
        if (attempted) return located;
        attempted = true;
        Cached cached = readCache();
        if (cached != null) {
            located = Optional.of(cached.country());
            if (Duration.between(cached.checkedAt(), clock.instant()).compareTo(Duration.ofHours(24)) < 0)
                return located;
        }
        try {
            byte[] bytes = transport.fetch();
            if (bytes.length > MAX_BODY) throw new IOException("Country lookup response too large");
            JsonObject response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!response.has("success") || !response.get("success").getAsBoolean()) return located;
            String country = normalize(response.get("country_code").getAsString());
            if (country != null) {
                located = Optional.of(country);
                save(country);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception unavailable) { /* A stale country is more useful than a failed lookup. */ }
        return located;
    }

    private record Cached(String country, Instant checkedAt) { }
    private Cached readCache() {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) return null;
            JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String country = normalize(value.get("country").getAsString());
            Instant checkedAt = Instant.parse(value.get("checkedAt").getAsString());
            return country == null || checkedAt.isAfter(clock.instant()) ? null : new Cached(country, checkedAt);
        } catch (IOException | RuntimeException invalid) { return null; }
    }
    private void save(String country) throws IOException {
        JsonObject value = new JsonObject(); value.addProperty("country", country);
        value.addProperty("checkedAt", clock.instant().toString());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".location-country-", ".tmp");
        try {
            Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String normalize(String country) {
        if (country == null) return null;
        String code = country.strip().toUpperCase(Locale.ROOT);
        return COUNTRIES.contains(code) ? code : null;
    }
    private static byte[] fetchCountry() throws Exception {
        ProxySelector direct = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
        };
        HttpClient client = HttpClient.newBuilder().proxy(direct).connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://ipwho.is/?fields=success,country_code"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> boundedBody(MAX_BODY));
        try {
            HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IOException("Country lookup unavailable");
            return response.body();
        } finally { pending.cancel(true); }
    }
    /** Enforces a streaming cap before accumulating the response, including chunked bodies. */
    static HttpResponse.BodySubscriber<byte[]> boundedBody(int limit) {
        HttpResponse.BodySubscriber<byte[]> target = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long received;
            private boolean failed;
            public CompletionStage<byte[]> getBody() { return target.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; target.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (ByteBuffer buffer : buffers) received += buffer.remaining();
                if (received > limit) {
                    failed = true; subscription.cancel(); target.onError(new IOException("Country lookup response too large"));
                } else target.onNext(buffers);
            }
            public void onError(Throwable failure) { if (!failed) { failed = true; target.onError(failure); } }
            public void onComplete() { if (!failed) target.onComplete(); }
        };
    }

    /** Stable country-centroid distance ordering, not individual endpoint geolocation. */
    public static List<Server> prioritize(List<Server> servers, String country) {
        String home = normalize(country);
        if (home == null || !CENTROIDS.containsKey(home)) return List.copyOf(servers);
        List<Server> ordered = new ArrayList<>(servers);
        ordered.sort(Comparator.comparingDouble(server -> {
            String code = normalize(server.country());
            if (home.equals(code)) return -1;
            double[] destination = code == null ? null : CENTROIDS.get(code);
            return destination == null ? Double.POSITIVE_INFINITY : distance(CENTROIDS.get(home), destination);
        }));
        return ordered;
    }
    private static double distance(double[] a, double[] b) {
        double latitude = Math.toRadians(b[0] - a[0]), longitude = Math.toRadians(b[1] - a[1]);
        double h = Math.pow(Math.sin(latitude / 2), 2) + Math.cos(Math.toRadians(a[0]))
                * Math.cos(Math.toRadians(b[0])) * Math.pow(Math.sin(longitude / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
    private static Map<String, double[]> loadCentroids() {
        Map<String, double[]> values = new HashMap<>();
        try (InputStream stream = ServerProximity.class.getResourceAsStream("/country-centroids.csv")) {
            if (stream == null) return values;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.readLine();
                for (String line; (line = reader.readLine()) != null;) {
                    String[] fields = line.split(",");
                    if (fields.length == 3 && normalize(fields[0]) != null && !fields[1].isBlank() && !fields[2].isBlank())
                        values.put(fields[0], new double[]{Double.parseDouble(fields[1]), Double.parseDouble(fields[2])});
                }
            }
        } catch (IOException | NumberFormatException invalid) { return Map.of(); }
        return Collections.unmodifiableMap(values);
    }
}
