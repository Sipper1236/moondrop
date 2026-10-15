package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerPerformanceTest {
    @Test void computesMedianReliabilityAndPreservesLatencyTimestampWhenDownloading(){
        var result=ServerPerformance.measured(List.of(900L,100L,200L),3);
        assertEquals(200,result.millis());assertEquals(1.0,result.reliability());assertTrue(result.hasQuality());
        var partial=ServerPerformance.measured(List.of(101L,200L),3);
        assertEquals(150,partial.millis());assertEquals(2.0/3,partial.reliability());
        var download=partial.withThroughput(1_000_000);
        assertEquals(partial.checkedAt(),download.checkedAt());assertTrue(download.throughputFresh());
        assertTrue(download.label().contains("67%"));assertTrue(download.label().contains("8.0 Mb/s"));
        assertTrue(partial.withThroughput(0).throughputFresh());
        assertThrows(IllegalArgumentException.class,()->partial.withThroughput(-1));
    }
    @Test void separatesUnknownFailureAndInvalidMetrics(){
        var failed=ServerPerformance.measured(List.of(),3);
        assertFalse(failed.successful());assertTrue(failed.hasQuality());assertEquals(0,failed.reliability());
        assertFalse(ServerPerformance.failed().hasQuality());assertFalse(ServerPerformance.verified(3).hasQuality());
        assertThrows(IllegalArgumentException.class,()->ServerPerformance.measured(List.of(1L,2L),1));
        assertThrows(IllegalArgumentException.class,()->ServerPerformance.measured(List.of(-1L),3));
        assertThrows(IllegalArgumentException.class,()->new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now(),4,4,-1,Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now(),-1,3,-1,Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now(),1,0,-1,Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now(),1,3,-2,Instant.EPOCH));
        assertFalse(new ServerPerformance(ServerPerformance.State.FAILED,0,Instant.now(),1,3,-1,Instant.EPOCH).completed());
        assertFalse(new ServerPerformance(ServerPerformance.State.VERIFIED,-1,Instant.now()).completed());
        assertFalse(new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now().plusSeconds(30)).completed());
        assertFalse(new ServerPerformance(ServerPerformance.State.VERIFIED,1,Instant.now(),3,3,1,Instant.now().plusSeconds(30)).completed());
    }
    @Test void rankingPrioritizesQualityReliabilityThenEstimatedTransferTime(){
        var full=ServerPerformance.measured(List.of(100L,100L,100L),3);
        var flaky=ServerPerformance.measured(List.of(1L),3);
        var legacy=ServerPerformance.verified(1);
        var download=full.withThroughput(1_000_000);
        var failedDownload=full.withThroughput(0);
        var ranked=List.of(legacy,flaky,failedDownload,full,download).stream().sorted(ServerPerformance.bestFirst()).toList();
        assertEquals(List.of(download,full,failedDownload,flaky,legacy),ranked);
        var old=Instant.now().minusSeconds(8*86400);
        assertFalse(new ServerPerformance(full.state(),full.millis(),full.checkedAt(),3,3,500_000,old).throughputFresh());
    }
}
