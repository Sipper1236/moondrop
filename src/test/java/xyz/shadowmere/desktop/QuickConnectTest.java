package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class QuickConnectTest {
    private Server server(String id, String country) {
        return new Server(id, "127.0.0.1", 1080, "aes-256-gcm", "test", country, "", "", true);
    }
    @Test void selectionBoundsLargeFeedAndBalancesSourcesAndCountries() {
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < 184; i++) servers.add(server("s" + i, i % 2 == 0 ? "CA" : "US"));
        for (int i = 0; i < 13000; i++) servers.add(server("gfp:" + i, i % 2 == 0 ? "DE" : "JP"));
        var selected = QuickConnect.selectCandidates(servers, 256, new Random(42));
        assertEquals(256, selected.size());
        assertEquals(256, new HashSet<>(selected).size());
        assertEquals(128, selected.stream().filter(s -> s.id().startsWith("gfp:")).count());
        for (String country : List.of("CA", "US", "DE", "JP"))
            assertEquals(64, selected.stream().filter(s -> s.country().equals(country)).count());
        assertTrue(selected.stream().anyMatch(s -> s.id().startsWith("gfp:") && Integer.parseInt(s.id().substring(4)) > 1000));
    }
    @Test void collectorIsBalancedAsAnIndependentSource() {
        List<Server> servers=new ArrayList<>();
        for(int i=0;i<1000;i++)for(String prefix:List.of("shadow-","gfp:","collector:"))servers.add(server(prefix+i,"US"));
        var selected=QuickConnect.selectCandidates(servers,255,new Random(1));
        for(String source:List.of("Shadowmere","GFP","V2rayCollector"))
            assertEquals(85,selected.stream().filter(s->s.sourceName().equals(source)).count());
    }
    @Test void nearbySelectionPrioritizesLocalCountryWhileKeepingSourcesRepresented() {
        List<Server> servers=new ArrayList<>();
        for(String prefix:List.of("gfp:","morpheus:"))for(int i=0;i<100;i++) {
            servers.add(server(prefix+"remote"+i,"JP"));servers.add(server(prefix+"local"+i,"CA"));
        }
        var selected=QuickConnect.selectCandidates(servers,20,new Random(1),"CA");
        assertEquals(20,selected.size());assertTrue(selected.stream().allMatch(s->s.country().equals("CA")));
        assertEquals(10,selected.stream().filter(s->s.sourceName().equals("GFP")).count());
    }
    @Test void nearbyHttpsShortlistPrefersDistanceOverTcpHandshakeTime() throws Exception {
        List<Server> servers=new ArrayList<>();
        for(int i=0;i<30;i++)servers.add(server("gfp:remote"+i,"JP"));
        for(int i=0;i<20;i++)servers.add(server("gfp:near"+i,"CA"));
        var shortlist=QuickConnect.discover(servers,TimeUnit.SECONDS.toNanos(2),s->s.country().equals("CA")?100:1,u->fail(),"CA");
        assertEquals(20,shortlist.size());assertTrue(shortlist.stream().allMatch(s->s.country().equals("CA")));
    }
    @Test void smallListsRetainEveryCandidate() {
        var servers = List.of(server("a", "CA"), server("gfp:b", "DE"));
        assertEquals(servers, QuickConnect.selectCandidates(servers, 256, new Random(1)));
    }
    @Test void completionOrderFindsFastRespondersAndReportsOnlyCompletedFailures() throws Exception {
        var blocked = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        List<ServerSpeedTester.Update> updates = new ArrayList<>();
        var slow = server("slow", "CA"); var fast = server("fast", "CA"); var failed = server("failed", "CA");
        long start = System.nanoTime();
        var found = QuickConnect.discover(List.of(slow, fast, failed), TimeUnit.MILLISECONDS.toNanos(250), s -> {
            if (s == slow) { blocked.countDown(); try { new CountDownLatch(1).await(); } finally { stopped.countDown(); } }
            if (s == failed) throw new IOException("refused");
            return 10;
        }, updates::add);
        assertEquals(List.of(fast), found);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(1, updates.size()); assertEquals(failed, updates.get(0).server());
    }
    @Test void cancellationInterruptsPendingProbesWithoutFailedMeasurements() throws Exception {
        var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        List<ServerSpeedTester.Update> updates = new CopyOnWriteArrayList<>();
        Thread runner = new Thread(() -> {
            try { QuickConnect.discover(List.of(server("a", "CA")), TimeUnit.SECONDS.toNanos(8), s -> {
                entered.countDown(); try { new CountDownLatch(1).await(); } finally { stopped.countDown(); } return 1;
            }, updates::add); }
            catch (InterruptedException expected) { cancelled.set(true); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        runner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        runner.interrupt(); runner.join(2000);
        assertFalse(runner.isAlive()); assertTrue(cancelled.get());
        assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertTrue(updates.isEmpty());
    }
    @Test void responsiveShortlistDoesNotLetOneSourceCrowdOutTheOthers() throws Exception {
        List<Server> servers=new ArrayList<>();
        for(int i=0;i<80;i++)servers.add(server("gfp:"+i,"US"));
        for(String source:List.of("shadow-","collector:","morpheus:"))for(int i=0;i<10;i++)servers.add(server(source+i,"US"));
        var shortlist=QuickConnect.discover(servers,TimeUnit.SECONDS.toNanos(2),s->s.id().startsWith("gfp:")?1:100,u->fail());
        assertEquals(20,shortlist.size());
        for(String source:List.of("Shadowmere","GFP","V2rayCollector","Morpheus"))
            assertEquals(5,shortlist.stream().filter(s->s.sourceName().equals(source)).count());
    }
    @Test void discoveryKeepsTwentyLowestTcpTimes() throws Exception {
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < 30; i++) servers.add(server("" + i, "CA"));
        var found = QuickConnect.discover(servers, TimeUnit.SECONDS.toNanos(2), s -> Long.parseLong(s.id()), u -> fail());
        assertEquals(servers.subList(0, 20), found);
    }
}
