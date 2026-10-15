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
    @Test void completedChecksRemainFreshLongEnoughForAFullScan() throws Exception {
        var cache = new PerformanceCache(directory); String key = PerformanceCache.serverKey(server("one"));
        assertTrue(new ServerPerformance(ServerPerformance.State.VERIFIED, 10, Instant.now().minusSeconds(600)).fresh());
        assertTrue(new ServerPerformance(ServerPerformance.State.FAILED, 0, Instant.now().minusSeconds(6*86400)).fresh());
        cache.save(Map.of(key, ServerPerformance.testing()));
        assertTrue(cache.load(List.of(server("one"))).isEmpty());
        cache.save(Map.of(key, ServerPerformance.failed()));
        assertEquals(ServerPerformance.State.FAILED, cache.load(List.of(server("one"))).get(key).state());
        assertFalse(new ServerPerformance(ServerPerformance.State.VERIFIED, 10, Instant.now().minusSeconds(7*86400+1)).fresh());
    }
    @Test void keepsOldCompletedResultsAndRetainsTemporarilyAbsentSources() throws Exception {
        var cache=new PerformanceCache(directory);Server old=server("old"),other=server("other");
        var historical=new ServerPerformance(ServerPerformance.State.VERIFIED,42,Instant.now().minusSeconds(30*86400));
        cache.save(Map.of(PerformanceCache.serverKey(old),historical));
        assertEquals(historical,cache.load(List.of(old)).get(PerformanceCache.serverKey(old)));
        cache.save(Map.of(PerformanceCache.serverKey(other),ServerPerformance.failed()));
        assertEquals(2,cache.load(List.of(old,other)).size());
        cache.save(Map.of(PerformanceCache.serverKey(old),ServerPerformance.testing()));
        assertEquals(historical,cache.load(List.of(old)).get(PerformanceCache.serverKey(old)));
    }
    @Test void invalidRecordsDoNotHideValidCheckpointsOrLeakFutureAndTransientResults() throws Exception {
        Server valid=server("valid"),bad=server("bad"),future=server("future");var cache=new PerformanceCache(directory);
        cache.save(Map.of(PerformanceCache.serverKey(valid),ServerPerformance.verified(12),
                PerformanceCache.serverKey(future),new ServerPerformance(ServerPerformance.State.FAILED,0,Instant.now().plusSeconds(60))));
        Path file=directory.resolve("server-performance.json");
        var root=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        root.getAsJsonObject("entries").addProperty(PerformanceCache.serverKey(bad),"broken");Files.writeString(file,root.toString());
        assertEquals(Set.of(PerformanceCache.serverKey(valid)),cache.load(List.of(valid,bad,future)).keySet());
    }
    @Test void retainsMeasurementsForLargeAdditionalFeed() throws Exception {
        List<Server> servers=new ArrayList<>();Map<String,ServerPerformance> values=new HashMap<>();
        for(int i=0;i<13_500;i++) {
            Server server=server("key-"+i);servers.add(server);values.put(PerformanceCache.serverKey(server),ServerPerformance.verified(100+i));
        }
        var cache=new PerformanceCache(directory);cache.save(values);
        assertEquals(values,new PerformanceCache(directory).load(servers));
    }
    @Test void malformedAndOversizedCachesAreIgnored() throws Exception {
        var file = directory.resolve("server-performance.json"); var cache = new PerformanceCache(directory);
        Files.writeString(file, "{invalid"); assertTrue(cache.load(List.of(server("one"))).isEmpty());
        Files.writeString(file, " ".repeat(8_388_609)); assertTrue(cache.load(List.of(server("one"))).isEmpty());
    }
}
