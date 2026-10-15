package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Small credential-free cache, keyed by a digest of connection identity. */
public final class PerformanceCache {
    private static final int MAX_BYTES = 8_388_608, MAX_ENTRIES = 25_000;
    private final Path file;
    public PerformanceCache(Path dataDir) { file = dataDir.resolve("server-performance.json"); }
    public static String serverKey(Server server) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(server.host(), Integer.toString(server.port()), server.method(), server.password())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public Map<String,ServerPerformance> load(List<Server> servers) throws IOException {
        Map<String,ServerPerformance> results = new HashMap<>();
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return results;
        Set<String> wanted = new HashSet<>();
        for (Server server : servers) wanted.add(serverKey(server));
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return results;
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) return results;
            JsonObject entries = root.getAsJsonObject("entries");
            if (entries.size() > MAX_ENTRIES) return results;
            for (var entry : entries.entrySet()) {
                if (!wanted.contains(entry.getKey())) continue;
                JsonObject item = entry.getValue().getAsJsonObject();
                ServerPerformance.State state = ServerPerformance.State.valueOf(item.get("state").getAsString());
                long millis = item.get("millis").getAsLong();
                ServerPerformance performance = new ServerPerformance(state, millis, Instant.parse(item.get("checkedAt").getAsString()));
                if (millis >= 0 && performance.fresh()) results.put(entry.getKey(), performance);
            }
        } catch (RuntimeException invalid) { results.clear(); }
        return results;
    }
    public void save(Map<String,ServerPerformance> values) throws IOException {
        JsonObject entries = new JsonObject();
        values.entrySet().stream().filter(e -> e.getKey().matches("[0-9a-f]{64}") && e.getValue() != null && e.getValue().fresh())
            .sorted(Comparator.comparing((Map.Entry<String,ServerPerformance> e) -> e.getValue().checkedAt()).reversed())
            .limit(MAX_ENTRIES).forEach(e -> {
                var value = e.getValue(); JsonObject item = new JsonObject();
                item.addProperty("state", value.state().name()); item.addProperty("millis", value.millis());
                item.addProperty("checkedAt", value.checkedAt().toString()); entries.add(e.getKey(), item);
            });
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("entries", entries);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Server performance cache is too large.");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".server-performance-", ".tmp");
        try { Files.write(temp, bytes); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temp); }
    }
}
