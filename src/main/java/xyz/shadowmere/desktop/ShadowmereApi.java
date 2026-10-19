package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class ShadowmereApi {
    private static final URI ENDPOINT = URI.create("https://shadowmere.xyz/api/proxies/?is_active=true&page_size=200");
    private final HttpClient client;
    public ShadowmereApi() {this(ServerCatalog.feedClient(0));}
    ShadowmereApi(HttpClient client) {this.client=Objects.requireNonNull(client);}

    public List<Server> fetch() throws Exception {
        LinkedHashMap<String, Server> servers = new LinkedHashMap<>();
        int total = 1;
        for (int page = 1; page <= total; page++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "&page=" + page))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                    .header("User-Agent", "Moondrop/0.4 (+https://github.com/shadowmere-xyz/shadowmere)").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Shadowmere returned HTTP " + response.statusCode() + ". Try refreshing later.");
            JsonElement root = JsonParser.parseString(response.body());
            JsonArray rows;
            if (root.isJsonArray()) rows = root.getAsJsonArray();
            else {
                JsonObject object = root.getAsJsonObject();
                rows = object.getAsJsonArray("results");
                if (object.has("total_pages")) total = Math.max(1, Math.min(100, object.get("total_pages").getAsInt()));
                else if (object.has("next") && !object.get("next").isJsonNull()) total = Math.min(100, page + 1);
            }
            if (rows == null) throw new IOException("Shadowmere returned an unrecognized server list.");
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                try {
                    if (row.has("is_active") && !row.get("is_active").getAsBoolean()) continue;
                    Server server = Server.parse("shadowmere:" + str(row, "id"), str(row, "url"), str(row, "location_country_code"),
                            str(row, "location"), str(row, "last_checked"), true);
                    PublicEndpoints.requirePublicLiteral(server);
                    if (!Server.supportsEncryptedMethod(server.method())) continue;
                    servers.putIfAbsent(PerformanceCache.serverKey(server), server);
                } catch (IllegalArgumentException ignored) { /* Unsupported entries do not hide the usable list. */ }
            }
            if (page < total) Thread.sleep(400); // API allows three requests per second.
        }
        if (servers.isEmpty()) throw new IOException("No compatible active servers were returned. Try again later.");
        return servers.values().stream().sorted(Comparator.comparing(Server::countryName).thenComparing(Server::displayName)).toList();
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
