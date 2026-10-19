package xyz.moondrop.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import xyz.shadowmere.desktop.Server;
import xyz.shadowmere.desktop.PublicEndpoints;

/** Serializes engine operations, retaining the current VPN when settings change. */
public final class AppController {
    public interface Listener {void onState(AppState state);}
    static final int AUTO_TEST_LIMIT=128, QUICK_CANDIDATE_LIMIT=64, QUICK_RESPONSIVE_LIMIT=12, QUICK_WORKING_LIMIT=3;
    private static AppController instance;
    public static synchronized AppController get(Context context) {if(instance==null)instance=new AppController(context.getApplicationContext());return instance;}
    private final Context context;
    private final ServerRepository repository;
    private final SessionStore sessions;
    private volatile boolean restoring;
    private final SharedPreferences saved;
    private final BootstrapHistory bootstrap;
    private volatile int feedProxyPort;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners=new CopyOnWriteArraySet<>();
    private final ExecutorService network=Executors.newSingleThreadExecutor(),engine=Executors.newSingleThreadExecutor();
    private final Map<String,Measurement> measurements=new ConcurrentHashMap<>();
    private volatile List<Server> servers=List.of();
    private volatile Preferences preferences;
    private volatile boolean loading,testing,connected,connecting;
    private volatile Server connectedServer;
    private volatile String title="Disconnected",detail="Connect to the best available server",error="";
    private volatile int checked,working;
    private volatile long generation;
    private volatile Future<?> testingTask,connectionTask;
    private volatile MoondropVpnService session;
    private AppController(Context context) {
        this.context=context;sessions=new SessionStore(context);repository=new ServerRepository(context);saved=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        bootstrap=new BootstrapHistory(saved.getString("bootstrapHistory","{}"));
        preferences=new Preferences(saved.getBoolean("advanced",false),saved.getBoolean("testing",true),saved.getBoolean("nearby",true),saved.getStringSet("protocols",Preferences.PROTOCOLS),saved.getBoolean("privacy",false),saved.getString("manualCountry",""),saved.getString("entryKey",""),saved.getBoolean("confirmProxyContact",false),saved.getBoolean("automaticUpdates",true));
        ServerRepository.Snapshot cached=repository.load();if(repository.cacheUnavailable())error="Saved profiles could not be unlocked. They were kept intact; erase saved profiles in Settings to reset.";servers=cached.servers();measurements.putAll(cached.measurements());
        if(!servers.isEmpty())detail="Saved servers are ready · refreshing in background";
        refresh();
    }
    public Preferences preferences(){return preferences;}
    public void addListener(Listener listener){listeners.add(listener);main.post(()->{if(listeners.contains(listener))listener.onState(state());});}
    public void removeListener(Listener listener){listeners.remove(listener);}
    public AppState state(){List<Server> eligible=eligible();return new AppState(eligible,measurements,loading,testing,connected,connecting,connectedServer,title,detail,repository.cacheUnavailable()?"Saved profiles could not be unlocked. They are kept intact; erase saved profiles in Settings to reset.":error,checked,working);}
    private void emit(){AppState snapshot=state();main.post(()->{for(Listener listener:listeners)listener.onState(snapshot);});}
    private List<Server> eligible(){List<Server> result=new ArrayList<>();for(Server server:servers)if(preferences.enabledProtocols.contains(server.protocol()))result.add(server);result.sort(Comparator.comparingDouble((Server s)->{Measurement m=measurements.get(ServerRepository.key(s));return m!=null&&m.fresh()?m.score():Double.POSITIVE_INFINITY;}).thenComparing(Server::countryName));return result;}
    public void refresh(){
        if(loading)return;loading=true;error="";emit();network.submit(()->{
            boolean throughVpn=connected;
            try {
                int port=feedProxyPort;
                if(throughVpn&&port==0)throw new IOException("Protected refresh is unavailable");
                Proxy route=throughVpn?new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)):Proxy.NO_PROXY;
                List<Server> fresh=repository.fetch(route);synchronized(AppController.this){servers=bootstrap.retain(fresh,servers);persist();}
                if(!connected&&!connecting)detail="Choose Connect to find your best connection";
            }catch(Exception failure){error=servers.isEmpty()?"Could not load servers. On a censored network, open Settings → Import server links, or check your connection and refresh.":"Refresh failed. Your saved servers are ready to use.";}
            finally{loading=false;emit();if(!throughVpn&&connected)refresh();else if(preferences.automaticTesting)startTesting();}
        });
    }
    public synchronized int importProfiles(String links){
        if(links.length()>1024*1024)throw new IllegalArgumentException("Imported profiles are too large.");
        List<Server> imported=ServerRepository.parseFeed(links,"import:");
        if(imported.isEmpty())throw new IllegalArgumentException("No supported encrypted public server links found.");
        Map<String,Server> merged=new LinkedHashMap<>();for(Server server:servers)merged.put(ServerRepository.key(server),server);for(Server server:imported)merged.put(ServerRepository.key(server),server);
        servers=List.copyOf(merged.values());persist();detail="Imported servers are ready without a feed refresh";emit();return imported.size();
    }
    public void savePreferences(Preferences value){preferences=value;saved.edit().putBoolean("advanced",value.advancedMode).putBoolean("testing",value.automaticTesting).putBoolean("nearby",value.nearbyFirst).putBoolean("privacy",value.privacyMode).putBoolean("confirmProxyContact",value.confirmProxyContact).putBoolean("automaticUpdates",value.automaticUpdates).putString("manualCountry",value.manualCountry).putString("entryKey",value.entryKey).putStringSet("protocols",value.enabledProtocols).apply();cancelTesting();emit();if(value.automaticTesting&&!value.singleServerMode()&&!connected&&!connecting)startTesting();}
    public void connectBest(){requestConnection(null);}
    public void connect(Server server){if(!preferences.enabledProtocols.contains(server.protocol()))return;requestConnection(ServerRepository.key(server));}
    private void requestConnection(String key){if(connected||connecting)return;restoring=false;cancelTesting();connecting=true;title="Connecting";detail="Checking saved servers";error="";emit();Intent intent=new Intent(context,MoondropVpnService.class).setAction(MoondropVpnService.ACTION_CONNECT);if(key!=null)intent.putExtra(MoondropVpnService.EXTRA_SERVER_KEY,key);try{context.startForegroundService(intent);}catch(RuntimeException failure){connecting=false;title="Disconnected";error="Could not start the VPN. Allow VPN access and try again.";emit();}}
    public void disconnect(){cancelTesting();generation++;Future<?> active=connectionTask;if(active!=null)active.cancel(true);engine.submit(()->finish(session));}
    public void handleDisconnect(MoondropVpnService service){disconnect();}
    public void handleConnect(MoondropVpnService service,String serverKey){session=service;connecting=true;cancelTesting();long ticket=++generation;connectionTask=engine.submit(()->{
        try {
            List<Server> candidates=eligible();if(serverKey!=null)candidates.removeIf(server->!ServerRepository.key(server).equals(serverKey));
            if(!preferences.entryKey.isEmpty()){Server entry=entryServer();candidates.removeIf(candidate->candidate.usesUdpTransport()||ServerRepository.key(candidate).equals(ServerRepository.key(entry)));}
            if(preferences.singleServerMode()&&serverKey==null){candidates.sort(Comparator.comparingInt((Server s)->{Measurement m=measurements.get(ServerRepository.key(s));return m==null?2:m.verified?(m.fresh()?0:1):3;}).thenComparingDouble(s->{Measurement m=measurements.get(ServerRepository.key(s));return m!=null&&m.verified?m.score():Double.POSITIVE_INFINITY;}));candidates=new ArrayList<>(privacyCandidates(bootstrap.rank(candidates)));}
            if(candidates.isEmpty())throw new IOException("No enabled servers are available. Refresh or enable a protocol in Settings.");
            int tried=0,responsive=0;Map<Server,Measurement> verified=new LinkedHashMap<>();
            for(Server server:bootstrap.rank(preferences.singleServerMode()?candidates:prioritize(candidates))) {
                if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(tried++>=QUICK_CANDIDATE_LIMIT||responsive>=QUICK_RESPONSIVE_LIMIT)break;
                detail="Testing connection · attempt "+tried;service.updateNotification(detail);emit();
                try {
                    if(!preferences.singleServerMode()&&!tcpReachable(server)) {if(preferences.entryKey.isEmpty()){bootstrap.failed(server);saveBootstrap();}if(ticket!=generation)throw new InterruptedException();measurements.put(ServerRepository.key(server),new Measurement(-1,System.currentTimeMillis(),0,0,3));continue;}
                    responsive++;
                    Measurement old=measurements.get(ServerRepository.key(server));boolean cached=old!=null&&old.verified&&old.fresh();
                    Measurement measured=measure(server,ticket,!cached);
                    if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(cached&&measured.verified)measured=new Measurement(measured.latencyMs,measured.checkedAt,old.bytesPerSecond,measured.successes,measured.attempts);
                    if(preferences.entryKey.isEmpty()){measurements.put(ServerRepository.key(server),measured);persist();}emit();
                    if(measured.verified)verified.put(server,measured);else if(preferences.entryKey.isEmpty()){bootstrap.failed(server);saveBootstrap();}
                    if(measured.verified&&(preferences.singleServerMode()||cached||bootstrap.contains(server)||serverKey!=null||verified.size()>=QUICK_WORKING_LIMIT))break;
                }catch(InterruptedException cancelled){throw cancelled;}catch(Exception failure){NativeEngine.stop();if(preferences.entryKey.isEmpty()){bootstrap.failed(server);saveBootstrap();}if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();if(preferences.entryKey.isEmpty()){measurements.put(ServerRepository.key(server),new Measurement(-1,System.currentTimeMillis(),0,0,3));persist();}}
                if(serverKey!=null||preferences.singleServerMode())break;
            }
            List<Server> choices=new ArrayList<>(verified.keySet());choices.sort(Comparator.comparingDouble(server->verified.get(server).score()));
            for(Server server:choices)try {
                if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                detail="Applying protected connection";emit();int refreshPort=localPort();String approved=connectionConfiguration(server,refreshPort,true);NativeEngine.start(context,service,approved);
                try{sessions.save(server,approved);}catch(IOException failure){error="Connected, but the route could not be saved for Always-on restoration.";}
                if(ticket!=generation){NativeEngine.stop();throw new InterruptedException();}
                feedProxyPort=refreshPort;bootstrap.connected(preferences.entryKey.isEmpty()?server:entryServer(),System.currentTimeMillis());saveBootstrap();connectedServer=server;connected=true;connecting=false;title="Connected";detail=server.countryName()+" · "+server.protocolName();error="";service.updateNotification(detail);emit();refresh();return;
            }catch(InterruptedException cancelled){throw cancelled;}catch(Exception failure){NativeEngine.stop();}
            throw new IOException("The tested servers are unavailable. Refresh or try another server.");
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();finish(service);}catch(Exception failure){finish(service);error=failure instanceof IOException||failure instanceof IllegalArgumentException?failure.getMessage():"Could not establish a protected connection. Try another server.";emit();}
    });}
    public void handleRestore(MoondropVpnService service){
        if(connected||connecting)return;restoring=true;session=service;connecting=true;cancelTesting();long ticket=++generation;
        title="Restoring Device VPN";detail="Reusing the exact previously approved route";error="";emit();
        connectionTask=engine.submit(()->{try{
            SessionStore.Session previous=sessions.load();previous.server().outbound();
            JsonObject approved=JsonParser.parseString(previous.configuration()).getAsJsonObject();for(JsonElement item:approved.getAsJsonArray("outbounds"))if(!preferences.enabledProtocols.contains(item.getAsJsonObject().get("type").getAsString()))throw new IOException("The previously used protocol is disabled.");int port;try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
            JsonObject testingConfig=JsonParser.parseString(previous.configuration()).getAsJsonObject();JsonArray inbounds=new JsonArray();JsonObject mixed=new JsonObject();mixed.addProperty("type","mixed");mixed.addProperty("listen","127.0.0.1");mixed.addProperty("listen_port",port);inbounds.add(mixed);testingConfig.add("inbounds",inbounds);
            NativeEngine.start(context,service,testingConfig.toString());probe(port,restoreProbeOverride());NativeEngine.stop();
            if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
            int refreshPort=localPort();JsonObject restored=JsonParser.parseString(previous.configuration()).getAsJsonObject();JsonArray restoredInbounds=restored.getAsJsonArray("inbounds");for(int i=restoredInbounds.size()-1;i>=0;i--)if("mixed".equals(restoredInbounds.get(i).getAsJsonObject().get("type").getAsString()))restoredInbounds.remove(i);JsonObject refresh=new JsonObject();refresh.addProperty("type","mixed");refresh.addProperty("listen","127.0.0.1");refresh.addProperty("listen_port",refreshPort);restoredInbounds.add(refresh);restored.getAsJsonObject("route").addProperty("default_domain_resolver","encrypted-dns");NativeEngine.start(context,service,restored.toString());if(ticket!=generation)throw new InterruptedException();
            feedProxyPort=refreshPort;if(restored.getAsJsonArray("outbounds").size()==1){bootstrap.connected(previous.server(),System.currentTimeMillis());saveBootstrap();}connectedServer=previous.server();connected=true;connecting=false;title="Connected";detail="Restored Device VPN · "+previous.server().countryName();service.updateNotification(detail);emit();refresh();
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();finish(service);}catch(Exception failure){finish(service);error="The previous VPN route could not be restored ("+failure.getClass().getSimpleName()+"): "+restoreFailureSummary(failure)+" Open Moondrop and choose a connection. Android blocks other traffic only when Block connections without VPN is enabled.";emit();}});
    }
    private static String restoreFailureSummary(Exception failure) {
        String message=failure.getMessage();
        if(message==null||message.isBlank())return "No further detail was provided.";
        message=message.replaceAll("(?i)(?:ss|https?|vless|trojan|hysteria2|tuic)://\\S+","[server]")
                .replaceAll("(?i)(password|uuid|token)(\\s*[=:]\\s*)[^,}\\s]+","$1$2[redacted]");
        return message.length()>180?message.substring(0,180):message;
    }
    private void finish(MoondropVpnService service){feedProxyPort=0;NativeEngine.stop();connected=false;connecting=false;connectedServer=null;title="Disconnected";detail="Connect to the best available server";if(service!=null)service.finishSession();session=null;emit();}
    private void cancelTesting(){generation++;Future<?> task=testingTask;if(task!=null)task.cancel(true);testing=false;}
    private void startTesting(){if(connected||connecting||testing||!preferences.automaticTesting||preferences.singleServerMode()||restoring)return;testing=true;long ticket=++generation;testingTask=engine.submit(()->{try{List<Server> pending=pending(prioritize(eligible()),measurements);checked=0;working=0;for(Server server:pending){if(ticket!=generation||Thread.currentThread().isInterrupted()||connected||connecting)break;Measurement result=tcpReachable(server)?measure(server,ticket,working<5):new Measurement(-1,System.currentTimeMillis(),0,0,3);if(ticket!=generation||Thread.currentThread().isInterrupted())break;measurements.put(ServerRepository.key(server),result);checked++;if(result.verified)working++;if(!connected&&!connecting)detail="Finding your fastest connection · "+working+" working · "+checked+" checked";if(checked%5==0)persist();emit();}}catch(Exception ignored){}finally{persist();if(ticket==generation){testing=false;emit();}}});}
    static List<Server> privacyCandidates(List<Server> ranked) {return ranked.isEmpty()?List.of():List.of(ranked.get(0));}
    public void testServer(Server server) {
        if(connected||connecting)return;
        if(!preferences.enabledProtocols.contains(server.protocol()))return;
        if(!preferences.entryKey.isEmpty()&&(server.usesUdpTransport()||ServerRepository.key(server).equals(preferences.entryKey))){error="Two-hop testing requires a distinct TCP exit. Choose another server.";emit();return;}
        cancelTesting();testing=true;long ticket=++generation;checked=0;working=0;emit();
        testingTask=engine.submit(()->{try{Measurement result=measure(server,ticket,true);if(ticket==generation){if(preferences.entryKey.isEmpty())measurements.put(ServerRepository.key(server),result);checked=1;working=result.verified?1:0;persist();}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{if(ticket==generation){testing=false;detail="Single-server test finished";emit();}}});
    }
    public void eraseSavedProfiles(){cancelTesting();network.submit(()->{servers=List.of();measurements.clear();bootstrap.clear();saveBootstrap();try{repository.erase();sessions.erase();}catch(IOException failure){error="Could not erase saved profiles. Try again.";}detail=connected?"Current Device VPN stays connected; saved profiles erased":"Saved profiles erased. Refresh to load servers.";emit();});}
    static List<Server> pending(List<Server> ordered, Map<String,Measurement> measurements) {
        List<Server> result=new ArrayList<>();
        for(Server server:ordered) {
            Measurement old=measurements.get(ServerRepository.key(server));
            if(old==null||!old.fresh())result.add(server);
            if(result.size()==AUTO_TEST_LIMIT)break;
        }
        return result;
    }
    private List<Server> prioritize(List<Server> input){List<Server> result=new ArrayList<>(input);String local=preferences.manualCountry.isEmpty()?Locale.getDefault().getCountry():preferences.manualCountry;if(preferences.nearbyFirst)result.sort(Comparator.comparingInt((Server s)->{Measurement value=measurements.get(ServerRepository.key(s));return value!=null&&value.verified&&value.fresh()?0:1;}).thenComparingInt(s->s.country().equalsIgnoreCase(local)?0:1));return result;}
    private boolean tcpReachable(Server server) {if(server.usesUdpTransport())return true;try(Socket socket=new Socket()){MoondropVpnService active=session;if(active!=null&&!active.protect(socket))return false;socket.connect(new InetSocketAddress(PublicEndpoints.checkedAddress(server,endpointAnswers(server)),server.port()),1200);return true;}catch(IOException failure){return false;}}
    private Measurement measure(Server server,long ticket,boolean bandwidthCheck) throws InterruptedException {
        int successes=0;List<Long> latencies=new ArrayList<>();long bandwidth=0;
        try {
            int port;try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
            NativeEngine.start(context,session,connectionConfiguration(server,port,false));
            for(int i=0;i<3;i++){if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();try{latencies.add(probe(port));successes++;}catch(IOException ignored){}}
            if(successes>0&&ticket==generation&&bandwidthCheck)try{bandwidth=downloadSpeed(port);}catch(IOException ignored){}
        }catch(InterruptedException cancelled){throw cancelled;}catch(Exception ignored){}finally{NativeEngine.stop();}
        if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
        Collections.sort(latencies);return new Measurement(latencies.isEmpty()?-1:latencies.get(latencies.size()/2),System.currentTimeMillis(),bandwidth,successes,3);
    }
    static long probe(int port) throws IOException {
        return probe(port,null);
    }
    private static long probe(int port,String debugEndpoint) throws IOException {
        List<String> endpoints=debugEndpoint==null?List.of("https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"):List.of(debugEndpoint);
        IOException last=null;for(String endpoint:endpoints){long start=System.nanoTime();java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new URL(endpoint).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));connection.setConnectTimeout(3500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(false);try{int expected=debugEndpoint==null?204:200;if(connection.getResponseCode()!=expected)throw new IOException("Connection check failed");return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}catch(IOException failure){last=failure;}finally{connection.disconnect();}}throw last;
    }
    private String restoreProbeOverride(){
        if(!BuildConfig.DEBUG)return null;
        String endpoint=context.getSharedPreferences("moondrop-test",Context.MODE_PRIVATE).getString("restore-probe-url","");
        return endpoint.startsWith("http://198.18.0.42/")?endpoint:null;
    }
    private static long downloadSpeed(int port) throws IOException {HttpsURLConnection connection=(HttpsURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=262144").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));connection.setConnectTimeout(3000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);long start=System.nanoTime(),bytes=0;try{if(connection.getResponseCode()!=200)throw new IOException("Bandwidth check failed");try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[8192];int count;while(bytes<262144&&(count=input.read(buffer,0,(int)Math.min(buffer.length,262144-bytes)))!=-1){bytes+=count;if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(5))break;}}return bytes==262144?(long)(bytes/(Math.max(1,System.nanoTime()-start)/1e9)):0;}finally{connection.disconnect();}}
    private Server entryServer(){if(preferences.entryKey.isEmpty())return null;for(Server candidate:servers)if(ServerRepository.key(candidate).equals(preferences.entryKey)&&candidate.protocol().equals("shadowsocks")&&preferences.enabledProtocols.contains(candidate.protocol()))return candidate;throw new IllegalArgumentException("The two-hop entry is unavailable or disabled. Choose another entry in Settings.");}
    private InetAddress[] endpointAnswers(Server server)throws IOException{
        PublicEndpoints.requirePublicLiteral(server);
        android.net.ConnectivityManager manager=context.getSystemService(android.net.ConnectivityManager.class);
        for(android.net.Network candidate:manager.getAllNetworks()){android.net.NetworkCapabilities capabilities=manager.getNetworkCapabilities(candidate);if(capabilities!=null&&capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))return candidate.getAllByName(server.host());}
        throw new IOException("No underlying internet connection is available");
    }
    private JsonObject safeOutbound(Server server){try{return PublicEndpoints.outbound(server,endpointAnswers(server));}catch(IOException failure){throw new IllegalArgumentException("Could not safely resolve the proxy endpoint.",failure);}}
    private String connectionConfiguration(Server server,int port,boolean vpn){Server entry=entryServer();return configuration(server,port,vpn,entry,safeOutbound(server),entry==null?null:safeOutbound(entry));}
    static String configuration(Server server,int port,boolean vpn){return configuration(server,port,vpn,null);}
    static String configuration(Server server,int port,boolean vpn,Server entry){return configuration(server,port,vpn,entry,PublicEndpoints.outbound(server),entry==null?null:PublicEndpoints.outbound(entry));}
    private static String configuration(Server server,int port,boolean vpn,Server entry,JsonObject outbound,JsonObject first){JsonObject root=new JsonObject();JsonObject log=new JsonObject();log.addProperty("disabled",true);root.add("log",log);JsonArray inbounds=new JsonArray();JsonObject inbound=new JsonObject();if(vpn){inbound.addProperty("type","tun");inbound.addProperty("tag","tun-in");inbound.addProperty("interface_name","moondrop");JsonArray addresses=new JsonArray();addresses.add("172.19.0.1/30");addresses.add("fdfe:dcba:9876::1/126");inbound.add("address",addresses);inbound.addProperty("mtu",9000);inbound.addProperty("auto_route",false);inbound.addProperty("stack","gvisor");}else{inbound.addProperty("type","mixed");inbound.addProperty("listen","127.0.0.1");inbound.addProperty("listen_port",port);}inbounds.add(inbound);if(vpn&&port>0){JsonObject refresh=new JsonObject();refresh.addProperty("type","mixed");refresh.addProperty("listen","127.0.0.1");refresh.addProperty("listen_port",port);inbounds.add(refresh);}root.add("inbounds",inbounds);JsonArray outbounds=new JsonArray();outbound.addProperty("tag","proxy");if(entry!=null){if(!entry.protocol().equals("shadowsocks")||server.usesUdpTransport()||ServerRepository.key(entry).equals(ServerRepository.key(server)))throw new IllegalArgumentException("Two-hop requires a distinct Shadowsocks entry and a TCP exit");first.addProperty("tag","entry");outbounds.add(first);outbound.addProperty("detour","entry");}outbounds.add(outbound);root.add("outbounds",outbounds);JsonObject route=new JsonObject();route.addProperty("final","proxy");route.addProperty("auto_detect_interface",false);if(vpn){JsonObject dns=new JsonObject();JsonArray dnsServers=new JsonArray();JsonObject bootstrap=new JsonObject();bootstrap.addProperty("type","local");bootstrap.addProperty("tag","bootstrap");dnsServers.add(bootstrap);JsonObject dnsServer=new JsonObject();dnsServer.addProperty("type","https");dnsServer.addProperty("tag","encrypted-dns");dnsServer.addProperty("server","1.1.1.1");dnsServer.addProperty("server_port",443);dnsServer.addProperty("path","/dns-query");dnsServer.addProperty("detour","proxy");dnsServers.add(dnsServer);dns.add("servers",dnsServers);dns.addProperty("final","encrypted-dns");route.addProperty("default_domain_resolver","encrypted-dns");root.add("dns",dns);JsonArray rules=new JsonArray();JsonObject rule=new JsonObject();rule.addProperty("port",53);rule.addProperty("action","hijack-dns");rules.add(rule);route.add("rules",rules);}root.add("route",route);return root.toString();}
    private static int localPort() throws IOException {try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    private void saveBootstrap(){saved.edit().putString("bootstrapHistory",bootstrap.json()).apply();}
    private void persist(){try{repository.save(servers,measurements);}catch(IOException ignored){}}
    public String diagnostics(){AppState current=state();return "Moondrop Android\nCoverage: Device VPN (Moondrop excluded)\nState: "+current.title+"\nServers: "+current.servers.size()+"\nCached measurements: "+current.measurements.size()+"\nChecked: "+current.checked+"\nWorking: "+current.working+"\n"+(current.error.isEmpty()?"":current.error);}
}
