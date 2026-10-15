package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceResultsTest {
    private Server server(int number) {
        return new Server("" + number, "127.0.0.1", 8000 + number, "aes-256-gcm", "password", "", "", "", true);
    }
    @Test void freshCacheSkipsChecksAndRanksOnlyCurrentSuccesses() {
        Server fast = server(1), slow = server(2), stale = server(3), failed = server(4), unknown = server(5);
        var results = new PerformanceResults();
        results.replaceAll(Map.of(
            PerformanceCache.serverKey(fast), ServerPerformance.verified(30),
            PerformanceCache.serverKey(slow), ServerPerformance.verified(90),
            PerformanceCache.serverKey(stale), new ServerPerformance(ServerPerformance.State.VERIFIED, 1, Instant.now().minusSeconds(1801)),
            PerformanceCache.serverKey(failed), ServerPerformance.failed()));
        List<Server> all = List.of(slow, stale, fast, failed, unknown);
        assertEquals(List.of(stale, unknown), results.pending(all, false));
        assertEquals(all, results.pending(all, true));
        assertEquals(List.of(fast, slow), results.ranked(all));
    }
    @Test void quickConnectSkipsFreshFailuresButRetriesExpiredFailures() {
        var results = new PerformanceResults();
        Server failed = server(1), expired = server(2), unknown = server(3);
        results.record(failed, ServerPerformance.failed());
        results.record(expired, new ServerPerformance(ServerPerformance.State.FAILED, 0, Instant.now().minusSeconds(301)));
        assertEquals(List.of(expired, unknown), results.quickCandidates(List.of(failed, expired, unknown)));
    }
    @Test void forcedRetestKeepsCompletedCacheAndCancellationRestoresIt() {
        var results = new PerformanceResults(); Server server = server(1);
        var previous = ServerPerformance.verified(123); results.record(server, previous);
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
}
