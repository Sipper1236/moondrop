package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceCacheTest {
    @TempDir Path directory;
    static Server server(String password) { return new Server("id", "private-host.example", 8388, "aes-256-gcm", password, "US", "", "", true); }
    @Test void survivesRestartWithoutPersistingCredentialsAndInvalidatesCredentialChanges() throws Exception {
        Server server = server("private-password"); String key = PerformanceCache.serverKey(server);
        new PerformanceCache(directory).save(Map.of(key, ServerPerformance.verified(243)));
        String json = Files.readString(directory.resolve("server-performance.json"));
        assertFalse(json.contains(server.host())); assertFalse(json.contains(server.password()));
        assertEquals(243, new PerformanceCache(directory).load(List.of(server)).get(key).millis());
        assertTrue(new PerformanceCache(directory).load(List.of(server("changed"))).isEmpty());
    }
    @Test void completedOnlyWithDifferentSuccessAndFailureExpiry() throws Exception {
        var cache = new PerformanceCache(directory); String key = PerformanceCache.serverKey(server("one"));
        assertTrue(new ServerPerformance(ServerPerformance.State.VERIFIED, 10, Instant.now().minusSeconds(600)).fresh());
        assertFalse(new ServerPerformance(ServerPerformance.State.FAILED, 0, Instant.now().minusSeconds(600)).fresh());
        cache.save(Map.of(key, ServerPerformance.testing()));
        assertTrue(cache.load(List.of(server("one"))).isEmpty());
        cache.save(Map.of(key, ServerPerformance.failed()));
        assertEquals(ServerPerformance.State.FAILED, cache.load(List.of(server("one"))).get(key).state());
        assertFalse(new ServerPerformance(ServerPerformance.State.VERIFIED, 10, Instant.now().minusSeconds(1801)).fresh());
    }
    @Test void malformedAndOversizedCachesAreIgnored() throws Exception {
        var file = directory.resolve("server-performance.json"); var cache = new PerformanceCache(directory);
        Files.writeString(file, "{invalid"); assertTrue(cache.load(List.of(server("one"))).isEmpty());
        Files.writeString(file, " ".repeat(1_048_577)); assertTrue(cache.load(List.of(server("one"))).isEmpty());
    }
}
