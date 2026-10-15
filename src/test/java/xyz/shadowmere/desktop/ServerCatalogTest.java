package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ServerCatalogTest {
    private Server server(String id,String password) {return new Server(id,"host.test",443,"aes-256-gcm",password,"US","Example","",true);}
    private ServerCatalog catalog(ServerCatalog.Source... sources) {return new ServerCatalog(List.of(sources),Duration.ofSeconds(2));}
    @Test void mergesSourcesByConnectionIdentityAndRetainsDifferentCredentials() throws Exception {
        var shadow=server("shadow","one");var duplicate=server("gfp:dup","one");var different=server("gfp:two","two");
        var result=catalog(new ServerCatalog.Source("Shadowmere",()->List.of(shadow)),new ServerCatalog.Source("GFP",()->List.of(duplicate,different))).fetch();
        assertEquals(2,result.servers().size());assertTrue(result.servers().contains(shadow));assertFalse(result.servers().contains(duplicate));
        assertEquals(1,result.counts().get("Shadowmere"));assertEquals(1,result.counts().get("GFP"));assertTrue(result.warnings().isEmpty());
    }
    @Test void oneFailedFeedDoesNotHideOtherFeedOrExposeResponse() throws Exception {
        var result=catalog(new ServerCatalog.Source("Shadowmere",()->{throw new IOException("ss://private-credentials");}),
                new ServerCatalog.Source("GFP",()->List.of(server("gfp:one","one")))).fetch();
        assertEquals(1,result.servers().size());assertEquals(List.of("Shadowmere is unavailable. Refresh to try again."),result.warnings());
    }
    @Test void sourcesRunConcurrentlyAndTimeoutDoesNotDiscardCompletedFeed() throws Exception {
        CountDownLatch ready=new CountDownLatch(2),cancelled=new CountDownLatch(1);
        var catalog=new ServerCatalog(List.of(new ServerCatalog.Source("slow",()-> {
            ready.countDown();try {Thread.sleep(10_000);return List.of();}catch(InterruptedException interrupted){cancelled.countDown();throw interrupted;}
        }),new ServerCatalog.Source("GFP",()->{ready.countDown();assertTrue(ready.await(1,TimeUnit.SECONDS));return List.of(server("gfp:one","one"));})),Duration.ofMillis(500));
        var result=catalog.fetch();assertEquals(1,result.servers().size());assertTrue(cancelled.await(1,TimeUnit.SECONDS));
        assertEquals(List.of("slow is unavailable. Refresh to try again."),result.warnings());
    }
    @Test void allFailedOrEmptyFeedsProduceActionableError() {
        var error=assertThrows(IOException.class,()->catalog(new ServerCatalog.Source("GFP",List::of)).fetch());
        assertTrue(error.getMessage().contains("refresh"));
    }
}
