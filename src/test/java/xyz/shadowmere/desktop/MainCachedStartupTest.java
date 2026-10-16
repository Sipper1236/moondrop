package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual controller with a delayed feed, never an external request. */
@EnabledIfSystemProperty(named="moondrop.test.ui",matches="true")
class MainCachedStartupTest {
    @TempDir Path data;
    private final Server cached=new Server("cached","localhost",12345,"aes-256-gcm","fixture-password","CA","Toronto","",true);

    @Test void savedServersAndSpeedsAppearBeforeRefreshAndConnectionWorkerStaysAvailable() throws Exception {
        runScenario(false);
    }
    @Test void failedRefreshKeepsSavedServersAndSpeedsUsable() throws Exception {
        runScenario(true);
    }
    private void runScenario(boolean fail) throws Exception {
        new ServerCache(data).save(List.of(cached));
        ServerPerformance quality=ServerPerformance.measured(List.of(40L,45L,50L),3);
        new PerformanceCache(data).save(Map.of(PerformanceCache.serverKey(cached),quality));
        CountDownLatch fetching=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        Server updated=new Server("fresh","localhost",12346,"aes-256-gcm","new-fixture-password","US","New York","",true);
        ServerCatalog catalog=new ServerCatalog(List.of(new ServerCatalog.Source("fixture",() -> {
            calls.incrementAndGet();fetching.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));
            if(fail)throw new IOException("simulated unavailable feed");
            return List.of(updated);
        })),Duration.ofSeconds(6));
        Main app=new Main(data,catalog);
        set(app,"settings",new AppSettings(false,false,false,true));
        AtomicReference<AppWindow> view=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {AppWindow window=new AppWindow(app);set(app,"window",window);view.set(window);});
        AppWindow window=view.get();
        try {
            app.loadInitialServers();
            assertTrue(fetching.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list=get(window,"servers",JList.class);
                assertEquals(1,list.getModel().getSize());assertEquals(cached,list.getModel().getElementAt(0));
                assertEquals(quality,((Map<?,?>)get(window,"performance",Map.class)).get(cached));
                assertTrue(get(window,"quickConnect",JButton.class).isEnabled(),"Saved servers must be connectable while fetching");
                assertTrue(get(window,"discovery",JLabel.class).getText().contains("Saved servers ready"));
                assertFalse(get(window,"refresh",JButton.class).isEnabled());
            });
            assertEquals("available",get(app,"worker",ExecutorService.class).submit(() -> "available").get(1,TimeUnit.SECONDS));
            app.refresh();assertEquals(1,calls.get(),"Repeated refresh must not duplicate an in-flight fetch");
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(get(app,"refreshingCatalog",AtomicBoolean.class).get()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(get(app,"refreshingCatalog",AtomicBoolean.class).get());
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list=get(window,"servers",JList.class);
                assertEquals(fail?cached:updated,list.getModel().getElementAt(0));
                assertTrue(get(window,"quickConnect",JButton.class).isEnabled());
                assertTrue(get(window,"refresh",JButton.class).isEnabled());
                if(fail) {
                    assertEquals(quality,((Map<?,?>)get(window,"performance",Map.class)).get(cached));
                    assertTrue(get(window,"discovery",JLabel.class).getText().contains("Using saved servers"));
                }
            });
            assertEquals(List.of(fail?cached:updated),new ServerCache(data).load());
        } finally {
            release.countDown();get(app,"closing",AtomicBoolean.class).set(true);
            for(String name:List.of("catalogWorker","worker","speedWorker","updateWorker","monitor")) {
                ExecutorService executor=get(app,name,ExecutorService.class);executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);
            }
            SwingUtilities.invokeAndWait(() -> {get(window,"performanceRefresh",Timer.class).stop();window.dispose();});
        }
    }
    private static <T>T get(Object target,String name,Class<T> type) {
        try {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void set(Object target,String name,Object value) {
        try {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
}
