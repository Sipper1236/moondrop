package xyz.shadowmere.desktop;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerProximityTest {
    @TempDir Path data;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    private byte[] response(String country) { return ("{\"success\":true,\"country_code\":\"" + country + "\"}").getBytes(StandardCharsets.UTF_8); }
    private Server server(String id, String country) { return new Server(id,"127.0.0.1",1080,"aes-256-gcm","test",country,"","",true); }
    private void cache(String country, Instant time) throws IOException {
        Files.writeString(data.resolve("location-country.json"), "{\"country\":\"" + country + "\",\"checkedAt\":\"" + time + "\"}");
    }
    @Test void sameCountryThenNearestCountryThenStableUnknowns() {
        Server us=server("us","US"), ca=server("ca","CA"), jp=server("jp","JP"), unknown=server("unknown",""), invalid=server("invalid","ZZ");
        assertEquals(List.of(us,ca,jp,unknown,invalid),ServerProximity.prioritize(List.of(unknown,jp,invalid,ca,us),"US"));
        assertEquals(List.of(server("fr","FR"),server("de","DE"),server("au","AU")),ServerProximity.prioritize(List.of(server("au","AU"),server("de","DE"),server("fr","FR")),"FR"));
    }
    @Test void unknownHomeDoesNotReorderAndSameCountryTiesRemainStable() {
        var servers=List.of(server("a","US"),server("b","US"),server("c",null));
        assertEquals(servers,ServerProximity.prioritize(servers,null));
        assertEquals(servers,ServerProximity.prioritize(servers,"ZZ"));
        assertEquals(servers,ServerProximity.prioritize(servers,"us"));
    }
    @Test void successfulLookupWritesOnlyCountryAndTimestampAndFreshCacheSkipsNetwork() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var locator=new ServerProximity(data,()->{calls.incrementAndGet();return response("ca");},clock);
        assertEquals(Optional.of("CA"),locator.locate()); assertEquals(Optional.of("CA"),locator.locate()); assertEquals(1,calls.get());
        JsonObject saved=JsonParser.parseString(Files.readString(data.resolve("location-country.json"))).getAsJsonObject();
        assertEquals(Set.of("country","checkedAt"),saved.keySet());
        assertEquals(clock.instant().toString(),saved.get("checkedAt").getAsString());
        assertEquals(Optional.of("CA"),new ServerProximity(data,()->{fail("fresh cache must skip transport");return null;},clock).locate());
    }
    @Test void staleCacheIsFallbackAndTransientFailureIsOnlyAttemptedOnce() throws Exception {
        cache("DE",clock.instant().minus(Duration.ofHours(25)));
        AtomicInteger calls=new AtomicInteger();
        var locator=new ServerProximity(data,()->{calls.incrementAndGet();throw new IOException("offline");},clock);
        assertEquals(Optional.of("DE"),locator.locate()); assertEquals(Optional.of("DE"),locator.locate()); assertEquals(1,calls.get());
        var refreshed=new ServerProximity(data,()->response("FR"),clock);
        assertEquals(Optional.of("FR"),refreshed.locate());
    }
    @Test void missingInvalidAndFutureCachesDoNotProvideFalseLocation() throws Exception {
        ServerProximity.Transport unavailable=()->{throw new IOException("offline");};
        assertTrue(new ServerProximity(data,unavailable,clock).locate().isEmpty());
        cache("ZZ",clock.instant()); assertTrue(new ServerProximity(data,unavailable,clock).locate().isEmpty());
        cache("CA",clock.instant().plusSeconds(1)); assertTrue(new ServerProximity(data,unavailable,clock).locate().isEmpty());
        Files.writeString(data.resolve("location-country.json"),"broken");
        assertTrue(new ServerProximity(data,unavailable,clock).locate().isEmpty());
    }
    @Test void exact24HourCacheExpiresAndCountryOverrideNeedsNoNetwork() throws Exception {
        cache("CA",clock.instant().minus(Duration.ofHours(24)));
        assertEquals(Optional.of("FR"),new ServerProximity(data,()->response("FR"),clock).locate());
        String previous=System.getProperty("moondrop.country");
        try {
            System.setProperty("moondrop.country"," jp ");
            assertEquals(Optional.of("JP"),new ServerProximity(data,()->{fail("override must skip transport");return null;},clock).locate());
        } finally { if(previous==null)System.clearProperty("moondrop.country");else System.setProperty("moondrop.country",previous); }
    }
    @Test void rejectsInvalidOrOversizedProviderResponses() {
        assertTrue(new ServerProximity(data,()->response("ZZ"),clock).locate().isEmpty());
        assertTrue(new ServerProximity(data,()->"{\"success\":false,\"country_code\":\"CA\"}".getBytes(StandardCharsets.UTF_8),clock).locate().isEmpty());
        assertTrue(new ServerProximity(data,()->new byte[65_537],clock).locate().isEmpty());
    }
    @Test void streamedBodyLimitCancelsBeforeAccumulatingExcessChunks() throws Exception {
        var body=ServerProximity.boundedBody(8); AtomicBoolean cancelled=new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription(){public void request(long count){} public void cancel(){cancelled.set(true);}});
        body.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        assertTrue(cancelled.get()); assertThrows(ExecutionException.class,()->body.getBody().toCompletableFuture().get());
        var valid=ServerProximity.boundedBody(8);
        valid.onSubscribe(new Flow.Subscription(){public void request(long count){} public void cancel(){fail();}});
        valid.onNext(List.of(ByteBuffer.wrap(new byte[8]))); valid.onComplete();
        assertEquals(8,valid.getBody().toCompletableFuture().get().length);
    }
}
