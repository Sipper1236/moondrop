package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPolicyTest {
    private Server server(String id,String country){return new Server(id,id+".invalid",443,"aes-256-gcm","fixture",country,"","",true);}
    private Map<String,ServerPerformance> quality(Server server,ServerPerformance quality){return Map.of(PerformanceCache.serverKey(server),quality);}
    @Test void freshCachedQualityLeadsWithoutDnsOrCandidateProbes()throws Exception {
        Server cached=server("fast","JP"),local=server("untested","CA");
        assertEquals(cached,ConnectionPolicy.selectSingle(List.of(local,cached),quality(cached,ServerPerformance.verified(12)),"CA"));
    }
    @Test void selectsBestFreshQualityAheadOfCountry()throws Exception {
        Server slow=server("slow","CA"),fast=server("fast","JP");
        Map<String,ServerPerformance> values=new HashMap<>(quality(slow,ServerPerformance.verified(900)));
        values.putAll(quality(fast,ServerPerformance.verified(12)));
        assertEquals(fast,ConnectionPolicy.selectSingle(List.of(slow,fast),values,"CA"));
    }
    @Test void staleResultsDoNotBeatFreshResults()throws Exception {
        Server stale=server("stale","CA"),fresh=server("fresh","JP");
        Map<String,ServerPerformance> values=new HashMap<>(quality(stale,new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now().minus(Duration.ofDays(8)))));
        values.putAll(quality(fresh,ServerPerformance.verified(1000)));
        assertEquals(fresh,ConnectionPolicy.selectSingle(List.of(stale,fresh),values,"CA"));
    }
    @Test void previouslyFailedIsExcludedWhenUntestedAlternativeExists()throws Exception {
        Server failed=server("failed","CA"),untested=server("untested","JP");
        assertEquals(untested,ConnectionPolicy.selectSingle(List.of(failed,untested),quality(failed,ServerPerformance.failed()),"CA"));
    }
    @Test void countryOrderingIsStableAndIndependentOfFeedArrival()throws Exception {
        Server near=server("near","CA"),far=server("far","AU");
        assertEquals(near,ConnectionPolicy.selectSingle(List.of(far,near),Map.of(),"CA"));
        Server expected=ConnectionPolicy.selectSingle(List.of(far,near),Map.of(),null);
        assertEquals(expected,ConnectionPolicy.selectSingle(List.of(near,far),Map.of(),null));
    }
    @Test void rejectsEmptyOrUnsafeCandidatesWithoutNetworkIo() {
        assertThrows(IOException.class,()->ConnectionPolicy.selectSingle(List.of(),Map.of(),null));
        Server privateServer=new Server("feed:evil","127.0.0.1",80,"aes-256-gcm","fixture","","","",true);
        assertThrows(IOException.class,()->ConnectionPolicy.selectSingle(List.of(privateServer),Map.of(),null));
    }
}
