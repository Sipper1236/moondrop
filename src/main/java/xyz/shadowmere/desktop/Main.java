package xyz.shadowmere.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.StringSelection;
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
    private volatile AppSettings settings=AppSettings.defaults();
    private final CoreInstaller installer;
    private final SystemProxy system;
    private final AppUpdater updater;
    private final ExecutorService updateWorker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"app-updates");t.setDaemon(true);return t;});
    private final AtomicBoolean checkingUpdates=new AtomicBoolean();
    private record PendingUpdate(AppUpdater.Release release,Path jar) { }
    private volatile PendingUpdate pendingUpdate;
    private volatile AppUpdater.Release nativeUpdate;
    private final PerformanceCache performanceCache;
    private final ServerCache serverCache;
    private final ServerCatalog catalog;
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"catalog-refresh");t.setDaemon(true);return t;});
    private final AtomicBoolean refreshingCatalog=new AtomicBoolean();
    private final ServerProximity proximity;
    private volatile String nearbyCountry;
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
    private volatile String lastOperation="Starting";
    private volatile String lastErrorCategory="None";
    private AppWindow window;
    private FileChannel lockChannel;
    private FileLock lock;

    private Main(Path data) {this(data,new ServerCatalog());}
    Main(Path data,ServerCatalog catalog) { this.data=data;this.catalog=catalog;serverCache=new ServerCache(data);installer=new CoreInstaller(data); system=new SystemProxy(data);performanceCache=new PerformanceCache(data);proximity=new ServerProximity(data);updater=new AppUpdater(data); }

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
                    List<Server> list=new ServerCatalog().fetch().servers();
                    String nearby=new ServerProximity(directory).locate().orElse(null);
                    QuickConnect.Result best=new QuickConnect().find(list,core.binary(),directory,System.out::println,ignored -> { },nearby);
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
        String override=System.getenv("MOONDROP_UPDATE_DATA_DIR");
        if(override==null)override=System.getProperty("shadowmere.dataDir");
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
        try { settings=AppSettings.load(data); } catch(IOException ignored) { /* Defaults remain usable. */ }
        window=new AppWindow(this);
        window.setSystemProxySupported(system.isSupported());
        window.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){shutdown();}});
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closing.set(true); Future<?> running=task; if(running!=null) running.cancel(true);
            updateWorker.shutdownNow();catalogWorker.shutdownNow();
            cancelSpeedTests();speedWorker.shutdownNow();
            worker.shutdownNow();
            try {worker.awaitTermination(15,TimeUnit.SECONDS);speedWorker.awaitTermination(10,TimeUnit.SECONDS); cleanup();} catch(Exception ignored) { }
            releaseLock();
        },"shutdown-recovery"));
        window.setCoreReady(installer.ready());
        window.appendLog("Select a location or use Quick Connect.");
        window.appendLog("Local HTTP / SOCKS5 · 127.0.0.1:"+ProxyEngine.PORT);
        window.setVisible(true);
        if(settings.automaticUpdates())checkForUpdates();
        monitor.scheduleWithFixedDelay(() -> {if(settings.automaticUpdates())checkForUpdates();},6,6,TimeUnit.HOURS);
        monitor.scheduleWithFixedDelay(() -> {
            ProxyEngine current=engine;
            if(connected&&current!=null&&!current.alive()&&!closing.get()) {
                connected=false;
                submit("Recovering", () -> {cleanup(); ui(() -> window.setConnection("Disconnected","The engine stopped. Previous proxy settings restored.",false));});
            }
        },2,2,TimeUnit.SECONDS);
        submit("Loading servers", () -> {
            loadInitialServers();
            if(system.hasRecovery()) {system.restore(); log("Recovered proxy settings from the previous session.");}
            ProxyEngine.recover(installer.binary(),data);
            // Configurations contain public server credentials; remove remnants from an interrupted run.
            try(var files=Files.list(data)) {for(Path p:files.filter(p -> p.getFileName().toString().startsWith("session-")&&p.toString().endsWith(".json")).toList()) Files.deleteIfExists(p);}
        });
    }

    @Override public AppSettings settings() { return settings; }
    @Override public boolean saveSettings(AppSettings next) {
        try { next.save(data); }
        catch(IOException e) { window.showError("Could not save settings",safeMessage(e)); return false; }
        AppSettings previous=settings; settings=next;
        if(!next.nearbyFirst())nearbyCountry=null;
        boolean protocolsChanged=!next.enabledProtocols().equals(previous.enabledProtocols());
        if(protocolsChanged)window.setServers(eligibleServers());
        if(!next.automaticTesting()||protocolsChanged)cancelSpeedTests();
        if(next.automaticTesting()&&(protocolsChanged||!previous.automaticTesting())&&!servers.isEmpty())startSpeedTests(false);
        if(next.automaticUpdates()&&!previous.automaticUpdates())checkForUpdates();
        log("Settings saved.");
        return true;
    }

    private List<Server> eligibleServers() { return settings.eligible(servers); }

    @Override public void refresh() { refreshCatalog(); }

    /** Disk first: no feed or location request may delay the saved list. */
    void loadInitialServers() {
        try {
            List<Server> cached=serverCache.load();
            if(!cached.isEmpty()) {
                showServers(cached);
                log("Loaded "+cached.size()+" saved servers and their cached measurements.");
                ui(() -> {if(!connected)window.setConnection("Disconnected","Saved servers are ready. Choose Connect while the list refreshes.",false);});
            }
        } catch(IOException unavailable) { log("Could not read saved servers. Downloading a fresh list."); }
        refreshCatalog();
    }

    private void refreshCatalog() {
        if(closing.get()||!refreshingCatalog.compareAndSet(false,true))return;
        ui(() -> window.setCatalogRefreshing(true,!servers.isEmpty()));
        catalogWorker.submit(() -> {
            try {
                ServerCatalog.Result fresh=catalog.fetch();
                String country=settings.nearbyFirst()?proximity.locate().orElse(null):null;
                if(closing.get()||Thread.currentThread().isInterrupted()){refreshingCatalog.set(false);return;}
                // Only applying the completed response uses the connection worker. Feed I/O never blocks Connect.
                worker.submit(() -> {
                    try {if(!closing.get())applyCatalog(fresh,country);}
                    finally {refreshingCatalog.set(false);ui(() -> window.setCatalogRefreshing(false,false));}
                });
            } catch(InterruptedException|CancellationException ignored) {Thread.currentThread().interrupt();refreshingCatalog.set(false);}
            catch(Exception unavailable) {
                refreshingCatalog.set(false);
                log("Server refresh unavailable. Saved servers and measurements were kept.");
                ui(() -> {
                    window.setCatalogRefreshing(false,!servers.isEmpty());
                    if(servers.isEmpty()&&!connected)window.setConnection("No servers available","Could not download servers. Check your connection and use Refresh.",false);
                });
                if(!closing.get())worker.submit(() -> {if(settings.automaticTesting()&&!servers.isEmpty()&&!speedTesting)startSpeedTests(false);});
            }
        });
    }

    private void loadServers() throws Exception {
        applyCatalog(catalog.fetch(),settings.nearbyFirst()?proximity.locate().orElse(null):null);
    }

    private void applyCatalog(ServerCatalog.Result fresh,String country) {
        cancelSpeedTests();
        nearbyCountry=settings.nearbyFirst()?country:null;
        showServers(fresh.servers());
        try {serverCache.save(fresh.servers());}catch(IOException unavailable){log("Could not save the server list. Current servers are still usable.");}
        ui(() -> {if(!connected)window.setConnection("Disconnected","Choose Connect to find your best connection.",false);});
        log("Updated · "+fresh.servers().size()+" protected proxy servers");
        fresh.counts().forEach((source,count) -> log(source+" · "+count+" unique servers"));
        fresh.warnings().forEach(this::log);
        if(settings.automaticTesting())startSpeedTests(false);
    }

    private void showServers(List<Server> available) {
        servers=List.copyOf(available);
        try {performance.replaceAll(performanceCache.load(available));}
        catch(IOException unavailable) {log("Could not read saved measurements. Keeping completed checks from this session.");}
        Map<String,ServerPerformance> saved=performance.snapshot();
        ui(() -> {
            window.clearPerformance();window.setServers(settings.eligible(available));
            for(Server server:available) {ServerPerformance value=saved.get(PerformanceCache.serverKey(server));if(value!=null)window.setPerformance(server,value);}
        });
    }

    @Override public void installCore() {submit("Installing engine",this::prepareEngine);}
    @Override public void connect(Server server,boolean useSystem) {submit("Connecting", () -> {ensureEngine();connectNow(server,useSystem,null);});}
    @Override public void quickConnect(boolean useSystem) {
        cancelSpeedTests();
        submit("Finding a server", () -> {
            try {
            connectionStage("Checking saved servers","Looking for your best previously tested connection…");
            ensureEngine();
            if(servers.isEmpty()) loadServers();
            cancelSpeedTests();
            List<Server> eligible=eligibleServers();
            if(eligible.isEmpty())throw new IOException("No enabled protocols have servers. Enable a protocol in Settings or refresh the server list.");
            List<Server> ranked=performance.connectionCandidates(eligible,3);
            for(Server cached:ranked) {
                checkCancelled();
                ProxyEngine.ensurePortFree(ProxyEngine.PORT);
                connectionStage("Checking saved servers","Verifying a saved connection in "+cached.countryName()+"…");
                try {connectNow(cached,useSystem,null);log("Used cached server ranking; connection verified again.");return;}
                catch(Exception failure) {
                    if(!retryableServerFailure(cached,failure))throw failure;
                    connectionStage("Trying another server","The saved server is unavailable. Checking another connection…");
                }
            }
            List<Server> candidates=performance.quickCandidates(eligible);
            if(candidates.isEmpty()) {
                candidates=eligible;
                connectionStage("Rechecking servers","Saved servers are unavailable. Checking again for a working connection…");
            }
            QuickConnect.Result result=new QuickConnect().find(candidates,installer.binary(),data,progress -> {
                log(progress); connectionStage("Finding a server",progress);
            },update -> recordPerformance(update.server(),update.performance()),nearbyCountry,performance.snapshot());
            checkCancelled();
            log("Best of "+result.tested()+" tested · "+result.server().displayName()+" · median HTTPS "+result.millis()+" ms");
            try {connectNow(result.server(),useSystem,result.millis());}
            catch(Exception failure) {
                if(!retryableServerFailure(result.server(),failure))throw failure;
                // A working endpoint can disappear between the shortlist test and the real connection.
                Exception last=failure;
                Map<String,ServerPerformance> checked=performance.snapshot();
                for(Server alternative:performance.ranked(eligible).stream()
                        .filter(server -> !server.equals(result.server()))
                        .filter(server -> {ServerPerformance value=checked.get(PerformanceCache.serverKey(server));return value!=null&&value.fresh();})
                        .limit(3).toList()) {
                    checkCancelled();
                    connectionStage("Trying another server","The selected connection failed verification. Trying "+alternative.countryName()+"…");
                    try {connectNow(alternative,useSystem,null);return;}
                    catch(Exception next) {if(!retryableServerFailure(alternative,next))throw next;last=next;}
                }
                throw last;
            }
            } finally {if(settings.automaticTesting()&&!closing.get()&&!Thread.currentThread().isInterrupted())startSpeedTests(false);}
        });
    }

    @Override public void forceRetestSpeeds() {startSpeedTests(true);}

    @Override public void retestSpeeds() {
        if(speedTesting)cancelSpeedTests();else startSpeedTests(true);
    }

    private void startSpeedTests(boolean force) {
        cancelSpeedTests();
        List<Server> eligible=eligibleServers();
        if(eligible.isEmpty()) {ui(() -> window.setSpeedTesting(false,"No servers for enabled protocols"));return;}
        List<Server> pending=ServerProximity.prioritize(performance.pending(eligible,force),nearbyCountry);
        int reused=eligible.size()-pending.size();
        if(pending.isEmpty()&&performance.bandwidthCandidates(eligible,5).isEmpty()) {ui(() -> window.setSpeedTesting(false,"Using "+reused+" saved checks · 7-day cache"));return;}
        int generation=speedGeneration.incrementAndGet();speedTesting=true;
        ui(() -> window.setSpeedTesting(true,reused+" cached · "+pending.size()+" checks remaining…"));
        speedTask=speedWorker.submit(() -> {
            try {
                prepareEngine();
                if(speedGeneration.get()!=generation||closing.get()||Thread.currentThread().isInterrupted())return;
                ServerSpeedTester tester=new ServerSpeedTester();
                java.util.function.Consumer<ServerSpeedTester.Update> record=update -> {
                    synchronized(speedLock) {if(speedGeneration.get()==generation&&!closing.get())recordPerformance(update.server(),update.performance());}
                };
                java.util.function.Consumer<String> progress=text -> ui(() -> {if(speedGeneration.get()==generation)window.setSpeedTesting(true,reused+" cached · "+text);});
                tester.test(pending,installer.binary(),data,record,progress);
                if(speedGeneration.get()!=generation||closing.get()||Thread.currentThread().isInterrupted())return;
                Map<Server,ServerPerformance> downloads=new java.util.LinkedHashMap<>();
                Map<String,ServerPerformance> saved=performance.snapshot();
                for(Server candidate:performance.bandwidthCandidates(eligible,5))downloads.put(candidate,saved.get(PerformanceCache.serverKey(candidate)));
                tester.testThroughput(downloads,installer.binary(),data,record,progress);
            } catch(InterruptedException|CancellationException ignored) {Thread.currentThread().interrupt();}
            catch(Exception e) {if(speedGeneration.get()==generation&&!closing.get())log("Latency checks: "+safeMessage(e));}
            finally {
                synchronized(speedLock) {
                if(speedGeneration.get()==generation) {
                    speedTesting=false;resetPendingMeasurements();savePerformance();
                    ui(() -> window.setSpeedTesting(false,"Results saved · reused for 7 days"));
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
        ui(() -> window.setSpeedTesting(false,"Progress saved · 7-day cache"));
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

    static final class ServerConnectionFailure extends IOException {
        ServerConnectionFailure(IOException cause) {super(safeMessage(cause),cause);}
    }

    private boolean retryableServerFailure(Server server,Exception failure) {
        if(!(failure instanceof ServerConnectionFailure))return false;
        if(failure instanceof InterruptedException || Thread.currentThread().isInterrupted() || closing.get() || system.hasRecovery())return false;
        ServerPerformance result=performance.get(server);
        return result!=null&&result.state()==ServerPerformance.State.FAILED;
    }

    private void connectionStage(String stage,String description) {
        lastOperation=stage;
        ui(() -> window.setConnection(stage,description,false));
    }

    private void connectNow(Server server,boolean useSystem,Long measured) throws Exception {
        if(!settings.allows(server))throw new IOException("This protocol is disabled. Enable it in Settings before connecting.");
        cleanup(); checkCancelled();
        if(!installer.ready()) throw new IOException("Install the engine first.");
        ProxyEngine.ensurePortFree(ProxyEngine.PORT);
        ProxyEngine fresh=new ProxyEngine(installer.binary(),data); engine=fresh;
        try {
            connectionStage("Connecting","Starting your encrypted connection to "+server.countryName()+"…");
            fresh.start(Map.of(ProxyEngine.PORT,server),this::log);
            connectionStage("Verifying connection","Checking HTTPS through "+server.countryName()+"…");
            long ms;
            try {ms=ProxyEngine.probe(ProxyEngine.PORT);}
            catch(IOException e) {if(!Thread.currentThread().isInterrupted()){recordPerformance(server,ServerPerformance.failed());savePerformance();}throw new ServerConnectionFailure(e);}
            checkCancelled();
            ServerPerformance saved=performance.snapshot().get(PerformanceCache.serverKey(server));
            recordPerformance(server,saved!=null&&saved.successful()&&saved.hasQuality()?saved:ServerPerformance.verified(ms));savePerformance();
            if(useSystem) {
                connectionStage("Applying system proxy","Setting up this device to use the verified connection…");
                system.enable("127.0.0.1",ProxyEngine.PORT);
            }
            checkCancelled(); connected=true;lastOperation="Connected";lastErrorCategory="None";
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
            try {lastOperation=label;checkCancelled();action.run();}
            catch(InterruptedException|CancellationException e) {Thread.currentThread().interrupt();}
            catch(Exception e) {
                lastErrorCategory=diagnosticErrorCategory(e);
                log(label+": "+safeMessage(e));
                boolean connectionAction=(label.equals("Connecting")||label.equals("Finding a server"))&&!connected&&!system.hasRecovery();
                ui(() -> {
                    if(!connected)window.setConnection("Disconnected",connectionAction?"Could not connect. Try another server or retest saved results.":"The operation could not finish. Please try again.",false);
                    if(connectionAction)window.showConnectionError(label,safeMessage(e));else window.showError(label,safeMessage(e));
                });
            } finally {ui(() -> window.setBusy(false));}
        });
    }

    private void shutdown() {
        shutdown(false);
    }

    @Override public void checkForUpdates() {
        if(closing.get()||!checkingUpdates.compareAndSet(false,true))return;
        AppUpdater.Release installerUpdate=nativeUpdate;
        if(installerUpdate!=null){checkingUpdates.set(false);ui(() -> window.setNativeUpdateReady(installerUpdate.version()));return;}
        PendingUpdate waiting=pendingUpdate;
        if(waiting!=null){checkingUpdates.set(false);ui(() -> window.setUpdateReady(waiting.release().version()));return;}
        ui(() -> window.setUpdateStatus("Checking updates…",true));
        updateWorker.submit(() -> {
            try {
                var available=updater.check(AppUpdater.currentVersion());
                if(available.isEmpty()) {ui(() -> window.setUpdateStatus("Up to date · "+AppUpdater.currentVersion(),false));return;}
                AppUpdater.Release release=available.get();
                if("native".equals(System.getProperty("moondrop.distribution"))) {
                    nativeUpdate=release;ui(() -> window.setNativeUpdateReady(release.version()));
                    log("Moondrop "+release.version()+" is available. Download its complete installer to update the app and bundled Java together.");return;
                }
                ui(() -> window.setUpdateStatus("Downloading "+release.version()+"…",true));
                Path jar=updater.download(release,progress -> ui(() -> window.setUpdateStatus(progress,true)));
                if(!closing.get()) {
                    pendingUpdate=new PendingUpdate(release,jar);
                    ui(() -> window.setUpdateReady(release.version()));log("Moondrop "+release.version()+" is verified and ready. Restart to update when convenient.");
                }
            } catch(InterruptedException|CancellationException interrupted) {Thread.currentThread().interrupt();}
            catch(Exception failure) {if(!closing.get()){ui(() -> window.setUpdateStatus("Update check unavailable",false));log("Updates: "+safeMessage(failure));}}
            finally {checkingUpdates.set(false);}
        });
    }

    @Override public void restartToUpdate() {
        AppUpdater.Release available=nativeUpdate;
        if(available!=null) {
            try {Desktop.getDesktop().browse(available.page());}
            catch(Exception failure){window.showError("Download update","Open "+available.page()+" to download the complete installer.");}
            return;
        }
        if(pendingUpdate!=null)shutdown(true);
    }

    private void shutdown(boolean installUpdate) {
        if(!closing.compareAndSet(false,true)) return;
        cancelSpeedTests();
        window.setBusy(true);
        Future<?> running=task;if(running!=null)running.cancel(true);
        worker.submit(() -> {
            try {
                Path plan=null;
                if(installUpdate) {
                    PendingUpdate ready=pendingUpdate;
                    if(ready==null)throw new IOException("The downloaded update is no longer available. Check for updates again.");
                    plan=UpdateInstaller.prepare(ready.jar(),ready.release().sha256(),ready.release().version(),data);
                }
                cleanup();
                if(plan!=null)UpdateInstaller.start(plan);
                updateWorker.shutdownNow();catalogWorker.shutdownNow();speedWorker.shutdownNow();speedWorker.awaitTermination(10,TimeUnit.SECONDS);
                monitor.shutdownNow();releaseLock();SwingUtilities.invokeLater(() -> {window.dispose();System.exit(0);});
            }
            catch(Exception e) {closing.set(false);ui(() -> {window.setBusy(false);window.showError(installUpdate?"Could not restart to update":"Restore proxy settings",safeMessage(e)+"\nThe app remains open so you can retry.");});}
        });
    }

    @Override public void copyDiagnostics() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(diagnostics()),null);
            JOptionPane.showMessageDialog(window,"Diagnostics copied. Server addresses, credentials, and raw logs are excluded.","Copy diagnostics",JOptionPane.INFORMATION_MESSAGE);
        } catch(Exception e) {window.showError("Copy diagnostics","Could not access the clipboard. Please try again.");}
    }

    String diagnostics() {
        Map<String,ServerPerformance> results=performance.snapshot();
        long working=results.values().stream().filter(ServerPerformance::successful).count();
        String version=Main.class.getPackage().getImplementationVersion();
        return "Moondrop diagnostics\nVersion: "+(version==null?"development":version)
                +"\nOS: "+System.getProperty("os.name")+" / "+System.getProperty("os.arch")
                +"\nJava: "+System.getProperty("java.version")
                +"\nConnection: "+(connected?"Connected":"Disconnected")
                +"\nLast operation: "+lastOperation+"\nLast error category: "+lastErrorCategory
                +"\nEngine ready: "+installer.ready()+"\nSystem proxy supported: "+system.isSupported()
                +"\nSystem proxy enabled in settings: "+settings.systemProxy()
                +"\nAutomatic testing: "+settings.automaticTesting()+"\nTesting active: "+speedTesting
                +"\nNearby first: "+settings.nearbyFirst()+"\nServers: "+servers.size()
                +"\nEnabled servers: "+eligibleServers().size()+"\nCached checks: "+results.size()
                +"\nWorking cached servers: "+working
                +"\nEnabled protocols: "+settings.enabledProtocols().stream().sorted().toList()+"\n";
    }

    static String diagnosticErrorCategory(Exception error) {
        for(Throwable current=error;current!=null;current=current.getCause()) {
            if(current instanceof javax.net.ssl.SSLException)return "TLS certificate or handshake";
            if(current instanceof java.net.SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException)return "Network timeout";
            if(current instanceof java.net.UnknownHostException)return "DNS lookup";
            if(current instanceof java.net.ConnectException)return "Connection refused or unreachable";
            if(current instanceof java.nio.file.AccessDeniedException || current instanceof SecurityException)return "Permission denied";
        }
        return error instanceof IOException?"Input/output failure":"Unexpected application failure";
    }

    private void releaseLock() {try {if(lock!=null&&lock.isValid())lock.release();if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){}}
    private void checkCancelled() throws InterruptedException {if(closing.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private void log(String text) {ui(() -> window.appendLog(text));}
    private void ui(Runnable callback) {if(!closing.get())SwingUtilities.invokeLater(callback);}
    static String safeMessage(Exception e) {String m=e.getMessage();return m==null?"The operation failed. Please try again.":m;}
    @FunctionalInterface private interface Throwing {void run() throws Exception;}
}
