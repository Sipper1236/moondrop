package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapHistoryTest {
    @TempDir Path data;
    private Server server(String id){return new Server(id,id+".invalid",443,"aes-256-gcm","secret-fixture","CA","","",true);}
    @Test void onlyActualEntrySuccessesSurviveRestartAndLeadRanking()throws Exception {
        Server entry=server("entry"),tested=server("benchmark");
        new PerformanceCache(data).save(Map.of(PerformanceCache.serverKey(tested),ServerPerformance.verified(1)));
        BootstrapHistory history=new BootstrapHistory(data);
        assertTrue(history.preferred(List.of(entry,tested)).isEmpty());
        history.succeeded(entry);
        assertEquals(List.of(entry),new BootstrapHistory(data).preferred(List.of(tested,entry)));
        assertFalse(Files.readString(data.resolve("bootstrap-history.json")).contains("secret-fixture"));
    }
    @Test void disappearingFeedRetainsProvenEntryButNotArbitraryOldServers()throws Exception {
        Server entry=server("entry"),old=server("old"),fresh=server("fresh");
        BootstrapHistory history=new BootstrapHistory(data);history.succeeded(entry);
        assertEquals(List.of(fresh,entry),history.retain(List.of(old,entry),List.of(fresh)));
        history.failed(entry);
        assertTrue(history.preferred(List.of(entry)).isEmpty());
        assertEquals(List.of(entry),history.retain(List.of(entry),List.of()));
        history.succeeded(entry);assertEquals(List.of(entry),history.preferred(List.of(entry)));
        history.erase();assertTrue(new BootstrapHistory(data).preferred(List.of(entry)).isEmpty());
    }
    @Test void feedDuplicateCannotEraseManualOwnership() {
        Server imported=server("manual:import"),remote=new Server("feed:duplicate",imported.host(),imported.port(),imported.method(),imported.password(),"US","","",true);
        BootstrapHistory history=new BootstrapHistory(data);
        assertEquals(List.of(imported),history.retain(List.of(imported),List.of(remote)));
        assertEquals(List.of(imported),history.retain(history.retain(List.of(imported),List.of(remote)),List.of()));
    }
    @Test void fallbackHistoryIsBounded()throws Exception {
        BootstrapHistory history=new BootstrapHistory(data);List<Server> servers=new ArrayList<>();
        for(int i=0;i<12;i++){Server s=server("s"+i);servers.add(s);history.succeeded(s);}
        assertEquals(8,new BootstrapHistory(data).preferred(servers).size());
    }
    @Test void corruptHistoryDoesNotPreventStartup()throws Exception {
        Files.writeString(data.resolve("bootstrap-history.json"),"invalid");
        assertTrue(new BootstrapHistory(data).preferred(List.of(server("s"))).isEmpty());
    }
}
