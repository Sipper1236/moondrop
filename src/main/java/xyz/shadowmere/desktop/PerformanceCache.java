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
    private static final int MAX_BYTES = 40 * 1024 * 1024, MAX_ENTRIES = 100_000;
    private final Path file;
    public PerformanceCache(Path dataDir) { file = dataDir.resolve("server-performance.json"); }
    public static String serverKey(Server server) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> identity=new ArrayList<>(List.of(server.host(), Integer.toString(server.port()), server.method(), server.password()));
            if(!server.protocol().equals("shadowsocks")){identity.add(server.protocol());identity.add(server.profileJson());}
            for (String value : identity) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public Map<String,ServerPerformance> load(List<Server> servers) throws IOException {
        Map<String,ServerPerformance> results=readCompleted();
        Set<String> wanted=new HashSet<>();for(Server server:servers)wanted.add(serverKey(server));
        results.keySet().retainAll(wanted);
        return results;
    }
    private Map<String,ServerPerformance> readCompleted() throws IOException {
        Map<String,ServerPerformance> results=new HashMap<>();
        if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)return results;
        try(var input=Files.newInputStream(file)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)return results;
            JsonObject root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            int version=root.get("version").getAsInt();if(version!=1&&version!=2)return results;
            JsonObject entries=root.getAsJsonObject("entries");if(entries.size()>MAX_ENTRIES)return results;
            for(var entry:entries.entrySet()) {
                try {
                    if(!entry.getKey().matches("[0-9a-f]{64}"))continue;
                    JsonObject item=entry.getValue().getAsJsonObject();
                    ServerPerformance.State state=ServerPerformance.State.valueOf(item.get("state").getAsString());
                    long millis=item.get("millis").getAsLong();Instant checkedAt=Instant.parse(item.get("checkedAt").getAsString());
                    ServerPerformance value=version==1?new ServerPerformance(state,millis,checkedAt):new ServerPerformance(state,millis,checkedAt,
                            item.get("successes").getAsInt(),item.get("attempts").getAsInt(),item.get("bytesPerSecond").getAsLong(),Instant.parse(item.get("throughputAt").getAsString()));
                    if(value.completed())results.put(entry.getKey(),value);
                }catch(RuntimeException invalidEntry){ /* Keep valid neighbouring checkpoints. */ }
            }
        }catch(RuntimeException invalidDocument){results.clear();}
        return results;
    }
    public synchronized void save(Map<String,ServerPerformance> values) throws IOException {
        // A temporarily missing feed must not erase its expensive completed measurements.
        Map<String,ServerPerformance> merged=readCompleted();
        values.forEach((key,value) -> {
            if(key==null||!key.matches("[0-9a-f]{64}")||value==null||!value.completed())return;
            merged.merge(key,value,(old,next)->next.checkedAt().isBefore(old.checkedAt())||next.checkedAt().equals(old.checkedAt())&&next.throughputAt().isBefore(old.throughputAt())?old:next);
        });
        JsonObject entries = new JsonObject();
        merged.entrySet().stream()
            .sorted(Comparator.comparing((Map.Entry<String,ServerPerformance> e) -> e.getValue().checkedAt()).reversed())
            .limit(MAX_ENTRIES).forEach(e -> {
                var value = e.getValue(); JsonObject item = new JsonObject();
                item.addProperty("state", value.state().name()); item.addProperty("millis", value.millis());
                item.addProperty("checkedAt", value.checkedAt().toString());
                item.addProperty("successes",value.successes());item.addProperty("attempts",value.attempts());
                item.addProperty("bytesPerSecond",value.bytesPerSecond());item.addProperty("throughputAt",value.throughputAt().toString());entries.add(e.getKey(), item);
            });
        JsonObject root = new JsonObject(); root.addProperty("version", 2); root.add("entries", entries);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Server performance cache is too large.");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".server-performance-", ".tmp");
        try { Files.write(temp, bytes); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temp); }
    }
}
