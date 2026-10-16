package xyz.shadowmere.desktop;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;

/** Private catalog snapshot, including the credentials required to reconnect offline. */
public final class ServerCache {
    private static final int MAX_BYTES = 40 * 1024 * 1024, MAX_ENTRIES = 100_000;
    private static final Gson GSON = new Gson();
    private final Path file;

    public ServerCache(Path dataDirectory) { file = dataDirectory.resolve("server-catalog.json"); }

    public List<Server> load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return List.of();
        makePrivate(file);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return List.of();
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) return List.of();
            JsonArray entries = root.getAsJsonArray("servers");
            if (entries.size() > MAX_ENTRIES) return List.of();
            List<Server> result = new ArrayList<>(entries.size());
            for (JsonElement entry : entries) {
                try {
                    Server server = GSON.fromJson(entry, Server.class);
                    if (safe(server)) result.add(server);
                } catch (RuntimeException invalidEntry) { /* Retain valid neighboring profiles. */ }
            }
            return List.copyOf(result);
        } catch (RuntimeException invalidDocument) {
            return List.of();
        }
    }

    public synchronized void save(List<Server> servers) throws IOException {
        if (servers.size() > MAX_ENTRIES) throw new IOException("Server catalog cache is too large.");
        Files.createDirectories(file.getParent());
        Path temporary;
        try {
            temporary = Files.createTempFile(file.getParent(), ".server-catalog-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            temporary = Files.createTempFile(file.getParent(), ".server-catalog-", ".tmp");
        }
        try {
            try (OutputStream raw = Files.newOutputStream(temporary);
                 OutputStream bounded = new FilterOutputStream(raw) {
                     private long count;
                     @Override public void write(int value) throws IOException {
                         check(1); out.write(value);
                     }
                     @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                         check(length); out.write(bytes, offset, length);
                     }
                     private void check(int length) throws IOException {
                         if ((count += length) > MAX_BYTES) throw new IOException("Server catalog cache is too large.");
                     }
                 };
                 JsonWriter writer = new JsonWriter(new OutputStreamWriter(bounded, StandardCharsets.UTF_8))) {
                writer.beginObject().name("version").value(1).name("cachedAt").value(Instant.now().toString());
                writer.name("servers").beginArray();
                for (Server server : servers) if (safe(server)) GSON.toJson(server, Server.class, writer);
                writer.endArray().endObject();
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (JsonIOException failure) {
            throw new IOException("Could not write the server catalog cache.",failure);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean safe(Server server) {
        if (server == null || !server.active()) return false;
        try {
            if (server.protocol().equals("shadowsocks")) return Server.supportsEncryptedMethod(server.method());
            server.outbound(); // Enforces an allowed protocol with verified TLS and no routing overrides.
            return true;
        } catch (RuntimeException invalidProfile) { return false; }
    }

    private static void makePrivate(Path path) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException unsupported) { /* Windows uses the user's profile permissions. */ }
    }
}
