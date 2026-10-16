package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceResultsTest {
    @Test void firstWorkingResultCanConnectBeforeDownloadTestsFinish() {
        var results=new PerformanceResults();
        Server first=server(1),stale=server(2),failed=server(3),unknown=server(4);
        results.record(first,ServerPerformance.measured(List.of(35L,40L,45L),3));
        results.record(stale,new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now().minusSeconds(8*86400),3,3,1000000,Instant.now().minusSeconds(8*86400)));
        results.record(failed,ServerPerformance.failed());
        assertFalse(results.get(first).throughputFresh());
        assertEquals(List.of(first),results.connectionCandidates(List.of(stale,failed,first,unknown),3));
        assertEquals(List.of(),results.connectionCandidates(List.of(first),0));
        assertThrows(IllegalArgumentException.class,() -> results.connectionCandidates(List.of(first),-1));
        results.record(first,ServerPerformance.failed());
        assertTrue(results.connectionCandidates(List.of(first),3).isEmpty());
    }
    private Server server(int number) {
        return new Server("" + number, "127.0.0.1", 8000 + number, "aes-256-gcm", "password", "", "", "", true);
    }
    @Test void cacheSkipsCompletedChecksAndReusesHistoricalSuccesses() {
        Server fast = server(1), slow = server(2), stale = server(3), failed = server(4), unknown = server(5);
        var results = new PerformanceResults();
        results.replaceAll(Map.of(
            PerformanceCache.serverKey(fast), ServerPerformance.verified(30),
            PerformanceCache.serverKey(slow), ServerPerformance.verified(90),
            PerformanceCache.serverKey(stale), new ServerPerformance(ServerPerformance.State.VERIFIED, 1, Instant.now().minusSeconds(7*86400+1)),
            PerformanceCache.serverKey(failed), ServerPerformance.failed()));
        List<Server> all = List.of(slow, stale, fast, failed, unknown);
        assertEquals(List.of(slow, stale, fast, unknown), results.pending(all, false));
        assertEquals(all, results.pending(all, true));
        assertEquals(List.of(stale, fast, slow), results.ranked(all));
    }
    @Test void quickConnectSkipsFreshFailuresButRetriesExpiredFailures() {
        var results = new PerformanceResults();
        Server failed = server(1), expired = server(2), unknown = server(3);
        results.record(failed, ServerPerformance.failed());
        results.record(expired, new ServerPerformance(ServerPerformance.State.FAILED, 0, Instant.now().minusSeconds(7*86400+1)));
        assertEquals(List.of(expired, unknown), results.quickCandidates(List.of(failed, expired, unknown)));
    }
    @Test void forcedRetestKeepsCompletedCacheAndCancellationRestoresIt() {
        var results = new PerformanceResults(); Server server = server(1);
        var previous = ServerPerformance.measured(List.of(123L,123L,123L),3); results.record(server, previous);
        results.record(server, ServerPerformance.testing());
        assertEquals(ServerPerformance.State.TESTING, results.get(server).state());
        assertEquals(previous, results.snapshot().get(PerformanceCache.serverKey(server)));
        assertTrue(results.pending(List.of(server), false).isEmpty());
        assertEquals(Map.of(server, previous), results.cancelTesting(List.of(server)));
        assertEquals(previous, results.get(server));
    }
    @Test void cancelledFirstMeasurementBecomesUntestedWithoutSavingTransientState() {
        var results = new PerformanceResults(); Server server = server(1);
        assertNull(results.get(server)); results.record(server, ServerPerformance.testing());
        assertTrue(results.snapshot().isEmpty());
        assertEquals(ServerPerformance.State.UNTESTED, results.cancelTesting(List.of(server)).get(server).state());
        assertNull(results.get(server)); assertTrue(results.isEmpty());
    }
    @Test void newCompletionReplacesOldMeasurementAndSnapshotIsDetached() {
        var results = new PerformanceResults(); Server server = server(1);
        results.record(server, ServerPerformance.verified(80)); results.record(server, ServerPerformance.testing());
        results.record(server, ServerPerformance.failed());
        assertTrue(results.cancelTesting(List.of(server)).isEmpty());
        assertEquals(ServerPerformance.State.FAILED, results.get(server).state());
        results.snapshot().clear(); assertFalse(results.isEmpty());
        results.replaceAll(Map.of()); assertTrue(results.isEmpty()); assertNull(results.get(server));
    }
    @Test void fullQualityRankingAndBandwidthCandidatesReuseIndependentMeasurements() {
        var results=new PerformanceResults();Server reliable=server(1),flaky=server(2),legacy=server(3),downloaded=server(4),failed=server(5);
        results.record(reliable,ServerPerformance.measured(List.of(100L,110L,120L),3));
        results.record(flaky,ServerPerformance.measured(List.of(1L),3));
        results.record(legacy,ServerPerformance.verified(1));
        results.record(downloaded,ServerPerformance.measured(List.of(100L,110L,120L),3).withThroughput(1_000_000));
        results.record(failed,ServerPerformance.failed());
        List<Server> all=List.of(legacy,flaky,reliable,downloaded,failed);
        assertEquals(List.of(downloaded,reliable,flaky,legacy),results.ranked(all));
        assertEquals(List.of(reliable),results.bandwidthCandidates(all,1));
        assertEquals(List.of(reliable,flaky),results.bandwidthCandidates(all,10));
        assertEquals(List.of(legacy),results.pending(all,false));
    }

    @Test void bandwidthShortlistDoesNotRotateToUnmeasuredServersOnEveryRestart(){
        var results=new PerformanceResults();List<Server> all=new ArrayList<>();
        for(int i=1;i<=8;i++){
            Server server=server(i);all.add(server);
            var quality=ServerPerformance.measured(List.of(i*10L,i*10L,i*10L),3);
            results.record(server,i<=5?quality.withThroughput(1_000_000):quality);
        }
        assertTrue(results.bandwidthCandidates(all,5).isEmpty(),"Cached shortlist must not trigger downloads through the remaining feed");
        Server expired=all.get(2);var previous=results.get(expired);
        results.record(expired,new ServerPerformance(previous.state(),previous.millis(),previous.checkedAt(),previous.successes(),previous.attempts(),previous.bytesPerSecond(),Instant.now().minusSeconds(8*86400)));
        assertEquals(List.of(expired),results.bandwidthCandidates(all,5));
        assertTrue(results.bandwidthCandidates(all,0).isEmpty());
    }

}
