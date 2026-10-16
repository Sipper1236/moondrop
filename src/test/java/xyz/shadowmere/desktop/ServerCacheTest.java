package xyz.shadowmere.desktop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerCacheTest {
    @TempDir Path directory;

    private static Server shadowsocks(String method, boolean active) {
        return new Server("source:id", "private.example", 8388, method, "secret", "CA", "Toronto", "2026-10-16", active);
    }
    private static Server protectedServer(boolean insecure) {
        String profile = "{\"type\":\"trojan\",\"server\":\"tls.example\",\"server_port\":443,"
                + "\"password\":\"private-auth\",\"tls\":{\"enabled\":true,\"insecure\":" + insecure
                + ",\"server_name\":\"camouflage.example\"},\"transport\":{\"type\":\"ws\",\"path\":\"/private\"}}";
        return new Server("collector:1", "tls.example", 443, "trojan", "private-auth", "DE", "Frankfurt", "", true, "trojan", profile);
    }

    @Test void roundTripsFullCredentialsMetadataAndProtectedProfilesAcrossRestart() throws Exception {
        List<Server> original = List.of(shadowsocks("aes-256-gcm", true), protectedServer(false));
        new ServerCache(directory).save(original);
        assertEquals(original, new ServerCache(directory).load());
        JsonObject root = JsonParser.parseString(Files.readString(directory.resolve("server-catalog.json"))).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertDoesNotThrow(() -> java.time.Instant.parse(root.get("cachedAt").getAsString()));
        assertEquals(protectedServer(false).outbound(), new ServerCache(directory).load().get(1).outbound());
    }

    @Test void excludesInactivePlaintextAndUnverifiedTlsProfiles() throws Exception {
        Server valid = shadowsocks("aes-256-gcm", true);
        new ServerCache(directory).save(Arrays.asList(valid, null, shadowsocks("none", true),
                shadowsocks("aes-256-gcm", false), protectedServer(true)));
        assertEquals(List.of(valid), new ServerCache(directory).load());
        Path file = directory.resolve("server-catalog.json");
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        Gson gson = new Gson();
        root.getAsJsonArray("servers").add(gson.toJsonTree(shadowsocks("none", true)));
        root.getAsJsonArray("servers").add(gson.toJsonTree(protectedServer(true)));
        root.getAsJsonArray("servers").add("malformed neighbor");
        Files.writeString(file, root.toString());
        assertEquals(List.of(valid), new ServerCache(directory).load());
    }

    @Test void ignoresMissingCorruptWrongVersionAndOversizedDocuments() throws Exception {
        ServerCache cache = new ServerCache(directory);
        assertTrue(cache.load().isEmpty());
        Path file = directory.resolve("server-catalog.json");
        for (String invalid : List.of("{invalid", "{}", "{\"version\":2,\"servers\":[]}", "[]")) {
            Files.writeString(file, invalid);
            assertTrue(cache.load().isEmpty());
        }
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(40L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertTrue(cache.load().isEmpty());
    }

    @Test void cacheIsPrivateOnPosixIncludingPreviouslyLoosePermissions() throws Exception {
        ServerCache cache = new ServerCache(directory);
        cache.save(List.of(shadowsocks("aes-256-gcm", true)));
        Path file = directory.resolve("server-catalog.json");
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return;
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(1, cache.load().size());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test void oversizedSavePreservesPreviousSnapshotWithoutTemporaryFiles() throws Exception {
        Server server = shadowsocks("aes-256-gcm", true);
        ServerCache cache = new ServerCache(directory);
        cache.save(List.of(server));
        assertThrows(IOException.class, () -> cache.save(Collections.nCopies(100_001, server)));
        Server hugeMetadata = new Server("large", server.host(), server.port(), server.method(), server.password(),
                "", "x".repeat(1024 * 1024), "", true);
        assertThrows(IOException.class, () -> cache.save(Collections.nCopies(41, hugeMetadata)));
        assertEquals(List.of(server), cache.load());
        try (var files = Files.list(directory)) {
            assertEquals(List.of("server-catalog.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }
}
