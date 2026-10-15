package xyz.shadowmere.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Main implements AppWindow.Actions {
    private final Path data;
    private final CoreInstaller installer;
    private final SystemProxy system;
    private final PerformanceCache performanceCache;
    private final PerformanceResults performance = new PerformanceResults();
    private final ExecutorService speedWorker = Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"speed-tests");t.setDaemon(true);return t;});
    private final AtomicInteger speedGeneration = new AtomicInteger();
    private final AtomicInteger measurements = new AtomicInteger();
    private final Object speedLock = new Object();
    private volatile Future<?> speedTask;
    private volatile boolean speedTesting;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r,"app-worker"));
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"connection-monitor");t.setDaemon(true);return t;});
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile List<Server> servers = List.of();
    private volatile Future<?> task;
    private volatile ProxyEngine engine;
    private volatile boolean connected;
    private AppWindow window;
    private FileChannel lockChannel;
    private FileLock lock;

    private Main(Path data) { this.data=data; installer=new CoreInstaller(data); system=new SystemProxy(data);performanceCache=new PerformanceCache(data); }

    public static void main(String[] args) {
        if(args.length>0 && (args[0].equals("--install-engine")||args[0].equals("--smoke-test"))) {
            try {
                Path directory=dataDirectory();CoreInstaller core=new CoreInstaller(directory);
                Files.createDirectories(directory);
                try(FileChannel channel=FileChannel.open(directory.resolve("app.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                    FileLock commandLock=channel.tryLock()) {
                if(commandLock==null)throw new IOException("Moondrop is already open. Close it before running a command-line check.");
                if(!core.ready()) core.install(System.out::println);
                if(args[0].equals("--smoke-test")) {
                    List<Server> list=new ShadowmereApi().fetch();
                    QuickConnect.Result best=new QuickConnect().find(list,core.binary(),directory,System.out::println);
                    try(ProxyEngine proxy=new ProxyEngine(core.binary(),directory)) {
                        ProxyEngine.ensurePortFree(ProxyEngine.PORT);
                        proxy.start(Map.of(ProxyEngine.PORT,best.server()),System.out::println);
                        System.out.println("Verified · "+best.server().displayName()+" · "+ProxyEngine.probe(ProxyEngine.PORT)+" ms HTTPS");
                    }
                }
                }
            } catch(Exception e) {System.err.println(safeMessage(e));System.exit(1);}
            return;
        }
        System.setProperty("apple.laf.useScreenMenuBar","true");
        System.setProperty("apple.awt.application.name","Moondrop");
        FlatDarkLaf.setup();
        UIManager.put("Button.arc",12); UIManager.put("Component.arc",12); UIManager.put("TextComponent.arc",12);
        UIManager.put("Component.focusColor",new Color(157,229,193));
        UIManager.put("defaultFont",new Font(Font.SANS_SERIF,Font.PLAIN,13));
        SwingUtilities.invokeLater(() -> new Main(dataDirectory()).launch());
    }

    static Path dataDirectory() {
        String override=System.getProperty("shadowmere.dataDir");
        if (override!=null) return Path.of(override).toAbsolutePath();
        String os=System.getProperty("os.name").toLowerCase();
        if(os.startsWith("mac")) return Path.of(System.getProperty("user.home"),"Library","Application Support","Shadowmere");
        if(os.startsWith("windows")) {
            String local=System.getenv("LOCALAPPDATA");
            return local==null?Path.of(System.getProperty("user.home"),"AppData","Local","Shadowmere"):Path.of(local,"Shadowmere");
        }
        return Path.of(System.getProperty("user.home"),".local","share","shadowmere-desktop");
    }

    private void launch() {
        try {
            Files.createDirectories(data);
            lockChannel=FileChannel.open(data.resolve("app.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock();
            if(lock==null) throw new IOException("Moondrop is already open.");
        } catch(Exception e) {
            JOptionPane.showMessageDialog(null,e.getMessage(),"Cannot open Moondrop",JOptionPane.ERROR_MESSAGE);
            releaseLock(); return;
        }
        window=new AppWindow(this);
        window.setSystemProxySupported(system.isSupported());
        window.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){shutdown();}});
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closing.set(true); Future<?> running=task; if(running!=null) running.cancel(true);
            cancelSpeedTests();speedWorker.shutdownNow();
            worker.shutdownNow();
            try {worker.awaitTermination(15,TimeUnit.SECONDS);speedWorker.awaitTermination(10,TimeUnit.SECONDS); cleanup();} catch(Exception ignored) { }
            releaseLock();
        },"shutdown-recovery"));
        window.setCoreReady(installer.ready());
        window.appendLog("Select a location or use Quick Connect.");
        window.appendLog("Local HTTP / SOCKS5 · 127.0.0.1:"+ProxyEngine.PORT);
        window.setVisible(true);
        monitor.scheduleWithFixedDelay(() -> {
            ProxyEngine current=engine;
            if(connected&&current!=null&&!current.alive()&&!closing.get()) {
                connected=false;
                submit("Recovering", () -> {cleanup(); ui(() -> window.setConnection("Disconnected","The engine stopped. Previous proxy settings restored.",false));});
            }
        },2,2,TimeUnit.SECONDS);
        submit("Loading servers", () -> {
            if(system.hasRecovery()) {system.restore(); log("Recovered proxy settings from the previous session.");}
            ProxyEngine.recover(installer.binary(),data);
            // Configurations contain public server credentials; remove remnants from an interrupted run.
            try(var files=Files.list(data)) {for(Path p:files.filter(p -> p.getFileName().toString().startsWith("session-")&&p.toString().endsWith(".json")).toList()) Files.deleteIfExists(p);}
            loadServers();
        });
    }

    @Override public void refresh() {submit("Refreshing servers",this::loadServers);}
    private void loadServers() throws Exception {
        cancelSpeedTests();
        if(!connected)ui(() -> window.setConnection("Loading servers","Getting active locations from Shadowmere…",false));
        List<Server> fresh=new ShadowmereApi().fetch(); servers=fresh;
        performance.replaceAll(Map.of());
        try {performance.replaceAll(performanceCache.load(fresh));}catch(IOException e){log("Could not read saved latency results. Servers will be checked again.");}
        Map<String,ServerPerformance> saved=performance.snapshot();
        ui(() -> {
            window.clearPerformance();window.setServers(fresh);
            for(Server server:fresh) {ServerPerformance p=saved.get(PerformanceCache.serverKey(server));if(p!=null)window.setPerformance(server,p);}
            if(!connected)window.setConnection("Disconnected","Choose Quick Connect or select a location.",false);
        });
        log("Updated · "+fresh.size()+" active Shadowsocks servers");
        startSpeedTests(false);
    }

    @Override public void installCore() {submit("Installing engine",this::prepareEngine);}
    @Override public void connect(Server server,boolean useSystem) {submit("Connecting", () -> {ensureEngine();connectNow(server,useSystem,null);});}
    @Override public void quickConnect(boolean useSystem) {
        cancelSpeedTests();
        submit("Finding a server", () -> {
            try {
            ensureEngine();
            if(servers.isEmpty()) loadServers();
            cancelSpeedTests();
            List<Server> ranked=performance.ranked(servers).stream().limit(3).toList();
            for(Server cached:ranked) {
                checkCancelled();
                ProxyEngine.ensurePortFree(ProxyEngine.PORT);
                try {connectNow(cached,useSystem,null);log("Used cached server ranking; connection verified again.");return;}
                catch(Exception failure) {
                    if(Thread.currentThread().isInterrupted()||closing.get()||system.hasRecovery())throw failure;
                    ServerPerformance result=performance.get(cached);
                    if(result==null||result.state()!=ServerPerformance.State.FAILED)throw failure;
                }
            }
            List<Server> candidates=performance.quickCandidates(servers);
            if(candidates.isEmpty())throw new IOException("No recently tested servers are working. Use Retest speeds or refresh the server list.");
            QuickConnect.Result result=new QuickConnect().find(candidates,installer.binary(),data,progress -> {
                log(progress); ui(() -> window.setConnection("Finding a server",progress,false));
            },update -> recordPerformance(update.server(),update.performance()));
            checkCancelled();
            log("Fastest of "+result.tested()+" tested · "+result.server().displayName()+" · "+result.millis()+" ms HTTPS");
            connectNow(result.server(),useSystem,result.millis());
            } finally {if(!closing.get()&&!Thread.currentThread().isInterrupted())startSpeedTests(false);}
        });
    }

    @Override public void retestSpeeds() {
        if(speedTesting)cancelSpeedTests();else startSpeedTests(true);
    }

    private void startSpeedTests(boolean force) {
        cancelSpeedTests();
        List<Server> pending=performance.pending(servers,force);
        if(pending.isEmpty()) {ui(() -> window.setSpeedTesting(false,"Using cached latency results"));return;}
        int generation=speedGeneration.incrementAndGet();speedTesting=true;
        ui(() -> window.setSpeedTesting(true,"Preparing latency checks…"));
        speedTask=speedWorker.submit(() -> {
            try {
                prepareEngine();
                if(speedGeneration.get()!=generation||closing.get()||Thread.currentThread().isInterrupted())return;
                new ServerSpeedTester().test(pending,installer.binary(),data,update -> {
                    synchronized(speedLock) {if(speedGeneration.get()==generation&&!closing.get())recordPerformance(update.server(),update.performance());}
                },progress -> ui(() -> {if(speedGeneration.get()==generation)window.setSpeedTesting(true,progress);}));
            } catch(InterruptedException|CancellationException ignored) {Thread.currentThread().interrupt();}
            catch(Exception e) {if(speedGeneration.get()==generation&&!closing.get())log("Latency checks: "+safeMessage(e));}
            finally {
                synchronized(speedLock) {
                if(speedGeneration.get()==generation) {
                    speedTesting=false;resetPendingMeasurements();savePerformance();
                    ui(() -> window.setSpeedTesting(false,"Latency results saved · fresh for 30 min"));
                }
                }
            }
        });
    }

    private void cancelSpeedTests() {
        synchronized(speedLock) {
        speedGeneration.incrementAndGet();speedTesting=false;
        Future<?> running=speedTask;if(running!=null)running.cancel(true);
        resetPendingMeasurements();savePerformance();
        ui(() -> window.setSpeedTesting(false,"Latency · cached for 30 min"));
        }
    }

    private void resetPendingMeasurements() {
        performance.cancelTesting(servers).forEach((server,result) -> ui(() -> window.setPerformance(server,result)));
    }

    private void recordPerformance(Server server,ServerPerformance value) {
        performance.record(server,value);
        ui(() -> window.setPerformance(server,value));
        if(value.state()!=ServerPerformance.State.TESTING&&measurements.incrementAndGet()%8==0)savePerformance();
    }

    private synchronized void savePerformance() {
        if(performance.isEmpty())return;
        try {performanceCache.save(performance.snapshot());}catch(IOException e){log("Could not save latency results to disk.");}
    }

    private void prepareEngine() throws Exception {
        synchronized(installer) {
            if(!installer.ready())installer.install(this::log);
        }
        ui(() -> window.setCoreReady(true));
    }

    private void ensureEngine() throws Exception {
        if(!installer.ready()) {
            ui(() -> window.setConnection("Installing engine","Preparing your first connection…",false));
            prepareEngine();checkCancelled();
        }
    }

    private void connectNow(Server server,boolean useSystem,Long measured) throws Exception {
        cleanup(); checkCancelled();
        if(!installer.ready()) throw new IOException("Install the engine first.");
        ProxyEngine.ensurePortFree(ProxyEngine.PORT);
        ProxyEngine fresh=new ProxyEngine(installer.binary(),data); engine=fresh;
        try {
            fresh.start(Map.of(ProxyEngine.PORT,server),this::log);
            ui(() -> window.setConnection("Verifying","Checking HTTPS through "+server.countryName()+"…",false));
            long ms;
            try {ms=ProxyEngine.probe(ProxyEngine.PORT);}
            catch(IOException e) {if(!Thread.currentThread().isInterrupted()){recordPerformance(server,ServerPerformance.failed());savePerformance();}throw e;}
            checkCancelled();
            recordPerformance(server,ServerPerformance.verified(ms));savePerformance();
            if(useSystem) system.enable("127.0.0.1",ProxyEngine.PORT);
            checkCancelled(); connected=true;
            String detail=(useSystem?"System proxy":"Local proxy")+" · HTTPS "+(measured==null?ms:measured)+" ms";
            ui(() -> {window.selectServer(server);window.setConnection("Connected",detail,true);});
            log("Connected · "+server.displayName()+" · "+detail);
        } catch(Exception e) {try{cleanup();}catch(Exception restoreFailure){restoreFailure.addSuppressed(e);throw restoreFailure;}throw e;}
    }

    @Override public void disconnect() {
        Future<?> running=task; if(running!=null&&!running.isDone()) running.cancel(true);
        submit("Disconnecting", () -> {cleanup(); ui(() -> window.setConnection("Disconnected","Your previous proxy settings are restored.",false));log("Disconnected.");});
    }

    private void cleanup() throws Exception {
        // Rollback commands must finish even when cancellation interrupted the operation.
        boolean interrupted=Thread.interrupted();
        try {
            system.restore();
            connected=false;
            ProxyEngine current=engine;if(current!=null){current.close();engine=null;}
        } catch(Exception e) {
            ProxyEngine current=engine;connected=current!=null&&current.alive();
            if(connected)ui(() -> window.setConnection("Recovery needed","The local proxy is still running. Retry Disconnect to restore system settings.",true));
            throw e;
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }

    private void submit(String label,Throwing action) {
        if(closing.get()) return;
        ui(() -> window.setBusy(true));
        task=worker.submit(() -> {
            ui(() -> window.setBusy(true));
            try {checkCancelled();action.run();}
            catch(InterruptedException|CancellationException e) {Thread.currentThread().interrupt();}
            catch(Exception e) {
                log(label+": "+safeMessage(e));
                ui(() -> {if(!connected)window.setConnection("Disconnected","Could not complete the connection. Try another server.",false);window.showError(label,safeMessage(e));});
            } finally {ui(() -> window.setBusy(false));}
        });
    }

    private void shutdown() {
        if(!closing.compareAndSet(false,true)) return;
        cancelSpeedTests();
        window.setBusy(true);
        Future<?> running=task;if(running!=null)running.cancel(true);
        worker.submit(() -> {
            try {cleanup();speedWorker.shutdownNow();speedWorker.awaitTermination(10,TimeUnit.SECONDS);monitor.shutdownNow();releaseLock();SwingUtilities.invokeLater(() -> {window.dispose();System.exit(0);});}
            catch(Exception e) {closing.set(false);ui(() -> {window.setBusy(false);window.showError("Restore proxy settings",safeMessage(e)+"\nKeep the app open and retry Disconnect before closing.");});}
        });
    }

    private void releaseLock() {try {if(lock!=null&&lock.isValid())lock.release();if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){}}
    private void checkCancelled() throws InterruptedException {if(closing.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private void log(String text) {ui(() -> window.appendLog(text));}
    private void ui(Runnable callback) {if(!closing.get())SwingUtilities.invokeLater(callback);}
    static String safeMessage(Exception e) {String m=e.getMessage();return m==null?"The operation failed. Please try again.":m;}
    @Override public void openDataFolder() {
        try {if(Desktop.isDesktopSupported())Desktop.getDesktop().open(data.toFile());else throw new IOException("App files: "+data);}
        catch(Exception e) {window.showError("App files",data.toString());}
    }
    @FunctionalInterface private interface Throwing {void run() throws Exception;}
}
