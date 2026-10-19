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
    private final Server cached=new Server("cached","8.8.8.8",12345,"aes-256-gcm","fixture-password","CA","Toronto","",true);

    @Test void savedServersAndSpeedsAppearBeforeRefreshAndConnectionWorkerStaysAvailable() throws Exception {
        runScenario(false);
    }
    @Test void failedRefreshKeepsSavedServersAndSpeedsUsable() throws Exception {
        runScenario(true);
    }
    @Test void privacyStartupAndRefreshNeverContactTheCanaryProxy()throws Exception {
        try(java.net.ServerSocket canary=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            canary.setSoTimeout(350);
            Server candidate=new Server("manual:privacy-canary","127.0.0.1",canary.getLocalPort(),"aes-256-gcm","canary-fixture-only","CA","","",true);
            new ServerCache(data).save(List.of(candidate));
            ServerCatalog feed=new ServerCatalog(List.of(new ServerCatalog.Source("controlled fixture",()->List.of(candidate))),Duration.ofSeconds(3));
            Main app=new Main(data,feed);
            PrivacyOptions privacy=new PrivacyOptions(true,false,false,"",false,false,"",false,false);
            set(app,"settings",new AppSettings(false,true,true,false,java.util.Set.copyOf(AppSettings.PROTOCOLS),false,privacy));
            AtomicReference<AppWindow> view=new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{AppWindow window=new AppWindow(app);set(app,"window",window);view.set(window);});
            try {
                app.loadInitialServers();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(get(app,"refreshingCatalog",AtomicBoolean.class).get()&&System.nanoTime()<deadline)Thread.sleep(10);
                assertFalse(get(app,"refreshingCatalog",AtomicBoolean.class).get());
                assertFalse(get(app,"speedTesting",Boolean.class));
                assertNull(get(app,"speedTask",Future.class));
                assertNull(get(app,"engine",ProxyEngine.class));
                assertThrows(java.net.SocketTimeoutException.class,canary::accept,"Startup/refresh must not contact a candidate in Privacy mode");
                assertFalse(java.nio.file.Files.exists(data.resolve("location-country.json")));
            }finally{
                get(app,"closing",AtomicBoolean.class).set(true);
                for(String name:List.of("catalogWorker","worker","speedWorker","updateWorker","monitor")) {
                    ExecutorService executor=get(app,name,ExecutorService.class);executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);
                }
                SwingUtilities.invokeAndWait(()->{get(view.get(),"performanceRefresh",Timer.class).stop();view.get().dispose();});
            }
        }
    }
    @Test void aConcurrentRefreshCannotDowngradeAnOptedInEncryptedCatalog()throws Exception {
        CountDownLatch keyLoad=new CountDownLatch(1),allowKey=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<byte[]> key=new java.util.concurrent.atomic.AtomicReference<>();
        SecretStore vault=new SecretStore(){
            public byte[] load()throws IOException{keyLoad.countDown();try{if(!allowKey.await(5,TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException e){throw new IOException(e);}byte[] current=key.get();return current==null?null:current.clone();}
            public void save(byte[] value){key.set(value.clone());}
            public void erase(){key.set(null);}
        };
        ServerCache cache=new ServerCache(data,vault);cache.save(List.of(cached));
        Main app=new Main(data,new ServerCatalog(List.of(),Duration.ofSeconds(1)));
        set(app,"serverCache",cache);set(app,"servers",List.of(cached));set(app,"settings",new AppSettings(false,false,false,false));
        AtomicReference<AppWindow> view=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{AppWindow window=new AppWindow(app);set(app,"window",window);view.set(window);});
        ExecutorService writers=Executors.newFixedThreadPool(2);
        try {
            AppSettings secure=new AppSettings(false,false,false,false,java.util.Set.copyOf(AppSettings.PROTOCOLS),false,new PrivacyOptions(false,false,false,"",true,false,"",false,false));
            Future<Boolean> enabling=writers.submit(()->app.saveSettings(secure));
            assertTrue(keyLoad.await(3,TimeUnit.SECONDS));
            AtomicReference<Thread> writerThread=new AtomicReference<>();
            var save=Main.class.getDeclaredMethod("saveCatalog",List.class);save.setAccessible(true);
            Future<?> refresh=writers.submit(()->{writerThread.set(Thread.currentThread());try{save.invoke(app,List.of(cached));}catch(Exception e){throw new RuntimeException(e);}});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((writerThread.get()==null||writerThread.get().getState()!=Thread.State.BLOCKED)&&System.nanoTime()<deadline)Thread.sleep(5);
            assertNotNull(writerThread.get());assertEquals(Thread.State.BLOCKED,writerThread.get().getState());
            allowKey.countDown();assertTrue(enabling.get(5,TimeUnit.SECONDS));refresh.get(5,TimeUnit.SECONDS);
            assertTrue(app.settings().privacy().secureCatalog());
            String metadata=java.nio.file.Files.readString(data.resolve("server-catalog.json"));
            assertTrue(com.google.gson.JsonParser.parseString(metadata).getAsJsonObject().get("version").getAsInt()==2);
            assertFalse(metadata.contains("fixture-password"));assertEquals(List.of(cached),cache.load());
        }finally{
            allowKey.countDown();writers.shutdownNow();get(app,"closing",AtomicBoolean.class).set(true);
            for(String name:List.of("catalogWorker","worker","speedWorker","updateWorker","monitor")){get(app,name,ExecutorService.class).shutdownNow();}
            SwingUtilities.invokeAndWait(()->{get(view.get(),"performanceRefresh",Timer.class).stop();view.get().dispose();});
        }
    }

    @Test void eraseInvalidatesAnInflightCountryLookupAndFeedRefresh()throws Exception {
        CountDownLatch locating=new CountDownLatch(1),finish=new CountDownLatch(1);
        ServerProximity location=new ServerProximity(data,()->{locating.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return "{\"success\":true,\"country_code\":\"CA\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);},java.time.Clock.systemUTC());
        Main app=new Main(data,new ServerCatalog(List.of(new ServerCatalog.Source("fixture",()->List.of(cached))),Duration.ofSeconds(3)));
        set(app,"proximity",location);set(app,"settings",new AppSettings(false,false,true,false,java.util.Set.copyOf(AppSettings.PROTOCOLS),false,new PrivacyOptions(true,false,true,"",false,false,"",false,false)));
        AtomicReference<AppWindow> view=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{AppWindow window=new AppWindow(app);set(app,"window",window);view.set(window);});
        try {
            app.loadInitialServers();assertTrue(locating.await(3,TimeUnit.SECONDS));set(app,"connected",true);app.eraseSavedData();
            finish.countDown();get(app,"worker",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS);
            get(app,"catalogWorker",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS);
            get(app,"worker",ExecutorService.class).submit(()->{}).get(5,TimeUnit.SECONDS);
            assertFalse(java.nio.file.Files.exists(data.resolve("location-country.json")));assertFalse(java.nio.file.Files.exists(data.resolve("server-catalog.json")));
            assertEquals(List.of(),get(app,"servers",List.class));
        }finally{
            finish.countDown();get(app,"closing",AtomicBoolean.class).set(true);
            for(String name:List.of("catalogWorker","worker","speedWorker","updateWorker","monitor")){get(app,name,ExecutorService.class).shutdownNow();}
            SwingUtilities.invokeAndWait(()->{get(view.get(),"performanceRefresh",Timer.class).stop();view.get().dispose();});
        }
    }

    private void runScenario(boolean fail) throws Exception {
        new ServerCache(data).save(List.of(cached));
        ServerPerformance quality=ServerPerformance.measured(List.of(40L,45L,50L),3);
        new PerformanceCache(data).save(Map.of(PerformanceCache.serverKey(cached),quality));
        CountDownLatch fetching=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        Server updated=new Server("fresh","1.1.1.1",12346,"aes-256-gcm","new-fixture-password","US","New York","",true);
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
