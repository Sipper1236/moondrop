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
        try(var output=Files.newByteChannel(file,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){output.position(40L*1024*1024);output.write(java.nio.ByteBuffer.wrap(new byte[]{0}));}
        assertTrue(cache.load(List.of(server("one"))).isEmpty());
    }
    @Test void upgradesVersionOneWithoutLosingLegacyResultsAndRoundTripsQuality() throws Exception {
        Server server=server("legacy");String key=PerformanceCache.serverKey(server);Instant checked=Instant.now().minusSeconds(60);
        var root=new com.google.gson.JsonObject();root.addProperty("version",1);
        var item=new com.google.gson.JsonObject();item.addProperty("state","VERIFIED");item.addProperty("millis",42);item.addProperty("checkedAt",checked.toString());
        var entries=new com.google.gson.JsonObject();entries.add(key,item);root.add("entries",entries);
        Files.writeString(directory.resolve("server-performance.json"),root.toString());
        var cache=new PerformanceCache(directory);var legacy=cache.load(List.of(server)).get(key);
        assertEquals(1,legacy.attempts());assertFalse(legacy.hasQuality());assertEquals(-1,legacy.bytesPerSecond());
        var quality=ServerPerformance.measured(List.of(100L,200L),3).withThroughput(500_000);
        cache.save(Map.of(key,quality));
        assertEquals(quality,cache.load(List.of(server)).get(key));
        assertEquals(2,com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("server-performance.json"))).getAsJsonObject().get("version").getAsInt());
        cache.save(Map.of(key,new ServerPerformance(quality.state(),quality.millis(),quality.checkedAt(),quality.successes(),quality.attempts(),-1,Instant.EPOCH)));
        assertEquals(quality,cache.load(List.of(server)).get(key),"Older snapshots must not erase a bandwidth checkpoint");
    }

    @Test void preservesShadowsocksKeysAndIncludesFullOtherProtocolSettings() throws Exception {
        Server original=server("secret");
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        for(String part:List.of(original.host(),"8388",original.method(),original.password())){
            byte[] bytes=part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
        }
        assertEquals(java.util.HexFormat.of().formatHex(digest.digest()),PerformanceCache.serverKey(original));
        Server first=new Server("a","same.example",443,"vless","auth","","","",true,"vless","{\"tls\":{\"server_name\":\"first.example\"}}");
        Server changed=new Server("b","same.example",443,"vless","auth","","","",true,"vless","{\"tls\":{\"server_name\":\"second.example\"}}");
        assertNotEquals(PerformanceCache.serverKey(first),PerformanceCache.serverKey(changed));
    }

    @Test void skipsMalformedQualityAndFutureDownloadTimestampsIndependently() throws Exception {
        var cache=new PerformanceCache(directory);Server valid=server("quality"),bad=server("negative"),future=server("future-download");
        String validKey=PerformanceCache.serverKey(valid);cache.save(Map.of(validKey,ServerPerformance.measured(List.of(10L,20L,30L),3)));
        Path file=directory.resolve("server-performance.json");var root=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        var entries=root.getAsJsonObject("entries");var negative=entries.getAsJsonObject(validKey).deepCopy();negative.addProperty("successes",-1);entries.add(PerformanceCache.serverKey(bad),negative);
        var futureItem=entries.getAsJsonObject(validKey).deepCopy();futureItem.addProperty("bytesPerSecond",123);futureItem.addProperty("throughputAt",Instant.now().plusSeconds(60).toString());entries.add(PerformanceCache.serverKey(future),futureItem);
        Files.writeString(file,root.toString());
        assertEquals(Set.of(validKey),cache.load(List.of(valid,bad,future)).keySet());
    }

}
