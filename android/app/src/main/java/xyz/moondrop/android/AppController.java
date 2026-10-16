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

/** Serializes engine operations, retaining the current VPN when settings change. */
public final class AppController {
    public interface Listener {void onState(AppState state);}
    static final int AUTO_TEST_LIMIT=128, QUICK_CANDIDATE_LIMIT=64, QUICK_RESPONSIVE_LIMIT=12, QUICK_WORKING_LIMIT=3;
    private static AppController instance;
    public static synchronized AppController get(Context context) {if(instance==null)instance=new AppController(context.getApplicationContext());return instance;}
    private final Context context;
    private final ServerRepository repository;
    private final SharedPreferences saved;
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
        this.context=context;repository=new ServerRepository(context);saved=context.getSharedPreferences("settings",Context.MODE_PRIVATE);
        preferences=new Preferences(saved.getBoolean("advanced",false),saved.getBoolean("testing",true),saved.getBoolean("nearby",true),saved.getStringSet("protocols",Preferences.PROTOCOLS));
        ServerRepository.Snapshot cached=repository.load();servers=cached.servers();measurements.putAll(cached.measurements());
        if(!servers.isEmpty())detail="Saved servers are ready · refreshing in background";
        refresh();
    }
    public Preferences preferences(){return preferences;}
    public void addListener(Listener listener){listeners.add(listener);main.post(()->{if(listeners.contains(listener))listener.onState(state());});}
    public void removeListener(Listener listener){listeners.remove(listener);}
    public AppState state(){List<Server> eligible=eligible();return new AppState(eligible,measurements,loading,testing,connected,connecting,connectedServer,title,detail,error,checked,working);}
    private void emit(){AppState snapshot=state();main.post(()->{for(Listener listener:listeners)listener.onState(snapshot);});}
    private List<Server> eligible(){List<Server> result=new ArrayList<>();for(Server server:servers)if(preferences.enabledProtocols.contains(server.protocol()))result.add(server);result.sort(Comparator.comparingDouble((Server s)->{Measurement m=measurements.get(ServerRepository.key(s));return m!=null&&m.fresh()?m.score():Double.POSITIVE_INFINITY;}).thenComparing(Server::countryName));return result;}
    public void refresh(){if(loading)return;loading=true;error="";emit();network.submit(()->{try {List<Server> fresh=repository.fetch();servers=fresh;persist();if(!connected&&!connecting)detail="Choose Connect to find your best connection";}catch(Exception failure){error=servers.isEmpty()?"Could not load servers. Check your connection and refresh.":"Refresh failed. Your saved servers are ready to use.";}finally{loading=false;emit();if(preferences.automaticTesting)startTesting();}});}
    public void savePreferences(Preferences value){preferences=value;saved.edit().putBoolean("advanced",value.advancedMode).putBoolean("testing",value.automaticTesting).putBoolean("nearby",value.nearbyFirst).putStringSet("protocols",value.enabledProtocols).apply();cancelTesting();emit();if(value.automaticTesting&&!connected&&!connecting)startTesting();}
    public void connectBest(){requestConnection(null);}
    public void connect(Server server){if(!preferences.enabledProtocols.contains(server.protocol()))return;requestConnection(ServerRepository.key(server));}
    private void requestConnection(String key){if(connected||connecting)return;cancelTesting();connecting=true;title="Connecting";detail="Checking saved servers";error="";emit();Intent intent=new Intent(context,MoondropVpnService.class).setAction(MoondropVpnService.ACTION_CONNECT);if(key!=null)intent.putExtra(MoondropVpnService.EXTRA_SERVER_KEY,key);try{context.startForegroundService(intent);}catch(RuntimeException failure){connecting=false;title="Disconnected";error="Could not start the VPN. Allow VPN access and try again.";emit();}}
    public void disconnect(){cancelTesting();generation++;Future<?> active=connectionTask;if(active!=null)active.cancel(true);engine.submit(()->finish(session));}
    public void handleDisconnect(MoondropVpnService service){disconnect();}
    public void handleConnect(MoondropVpnService service,String serverKey){session=service;connecting=true;cancelTesting();long ticket=++generation;connectionTask=engine.submit(()->{
        try {
            List<Server> candidates=eligible();if(serverKey!=null)candidates.removeIf(server->!ServerRepository.key(server).equals(serverKey));
            if(candidates.isEmpty())throw new IOException("No enabled servers are available. Refresh or enable a protocol in Settings.");
            int tried=0,responsive=0;Map<Server,Measurement> verified=new LinkedHashMap<>();
            for(Server server:prioritize(candidates)) {
                if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(tried++>=QUICK_CANDIDATE_LIMIT||responsive>=QUICK_RESPONSIVE_LIMIT)break;
                detail="Testing connection · attempt "+tried;service.updateNotification(detail);emit();
                try {
                    if(!tcpReachable(server)) {if(ticket!=generation)throw new InterruptedException();measurements.put(ServerRepository.key(server),new Measurement(-1,System.currentTimeMillis(),0,0,3));continue;}
                    responsive++;
                    Measurement old=measurements.get(ServerRepository.key(server));boolean cached=old!=null&&old.verified&&old.fresh();
                    Measurement measured=measure(server,ticket,!cached);
                    if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(cached&&measured.verified)measured=new Measurement(measured.latencyMs,measured.checkedAt,old.bytesPerSecond,measured.successes,measured.attempts);
                    measurements.put(ServerRepository.key(server),measured);persist();emit();
                    if(measured.verified)verified.put(server,measured);
                    if(measured.verified&&(cached||serverKey!=null||verified.size()>=QUICK_WORKING_LIMIT))break;
                }catch(InterruptedException cancelled){throw cancelled;}catch(Exception failure){NativeEngine.stop();if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();measurements.put(ServerRepository.key(server),new Measurement(-1,System.currentTimeMillis(),0,0,3));persist();}
                if(serverKey!=null)break;
            }
            List<Server> choices=new ArrayList<>(verified.keySet());choices.sort(Comparator.comparingDouble(server->verified.get(server).score()));
            for(Server server:choices)try {
                if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
                detail="Applying protected connection";emit();NativeEngine.start(context,service,configuration(server,0,true));
                if(ticket!=generation){NativeEngine.stop();throw new InterruptedException();}
                connectedServer=server;connected=true;connecting=false;title="Connected";detail=server.countryName()+" · "+server.protocolName();error="";service.updateNotification(detail);emit();return;
            }catch(InterruptedException cancelled){throw cancelled;}catch(Exception failure){NativeEngine.stop();}
            throw new IOException("The tested servers are unavailable. Refresh or try another server.");
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();finish(service);}catch(Exception failure){finish(service);error=failure instanceof IOException?failure.getMessage():"Could not establish a protected connection. Try another server.";emit();}
    });}
    private void finish(MoondropVpnService service){NativeEngine.stop();connected=false;connecting=false;connectedServer=null;title="Disconnected";detail="Connect to the best available server";if(service!=null)service.finishSession();session=null;emit();}
    private void cancelTesting(){generation++;Future<?> task=testingTask;if(task!=null)task.cancel(true);testing=false;}
    private void startTesting(){if(connected||connecting||testing||!preferences.automaticTesting)return;testing=true;long ticket=++generation;testingTask=engine.submit(()->{try{List<Server> pending=pending(prioritize(eligible()),measurements);checked=0;working=0;for(Server server:pending){if(ticket!=generation||Thread.currentThread().isInterrupted()||connected||connecting)break;Measurement result=tcpReachable(server)?measure(server,ticket,working<5):new Measurement(-1,System.currentTimeMillis(),0,0,3);if(ticket!=generation||Thread.currentThread().isInterrupted())break;measurements.put(ServerRepository.key(server),result);checked++;if(result.verified)working++;if(!connected&&!connecting)detail="Finding your fastest connection · "+working+" working · "+checked+" checked";if(checked%5==0)persist();emit();}}catch(Exception ignored){}finally{persist();if(ticket==generation){testing=false;emit();}}});}
    static List<Server> pending(List<Server> ordered, Map<String,Measurement> measurements) {
        List<Server> result=new ArrayList<>();
        for(Server server:ordered) {
            Measurement old=measurements.get(ServerRepository.key(server));
            if(old==null||!old.fresh())result.add(server);
            if(result.size()==AUTO_TEST_LIMIT)break;
        }
        return result;
    }
    private List<Server> prioritize(List<Server> input){List<Server> result=new ArrayList<>(input);String local=Locale.getDefault().getCountry();if(preferences.nearbyFirst)result.sort(Comparator.comparingInt((Server s)->{Measurement value=measurements.get(ServerRepository.key(s));return value!=null&&value.verified&&value.fresh()?0:1;}).thenComparingInt(s->s.country().equalsIgnoreCase(local)?0:1));return result;}
    private static boolean tcpReachable(Server server) {if(server.usesUdpTransport())return true;try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(server.host(),server.port()),1200);return true;}catch(IOException failure){return false;}}
    private Measurement measure(Server server,long ticket,boolean bandwidthCheck) throws InterruptedException {
        int successes=0;List<Long> latencies=new ArrayList<>();long bandwidth=0;
        try {
            int port;try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
            NativeEngine.start(context,null,configuration(server,port,false));
            for(int i=0;i<3;i++){if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();try{latencies.add(probe(port));successes++;}catch(IOException ignored){}}
            if(successes>0&&ticket==generation&&bandwidthCheck)try{bandwidth=downloadSpeed(port);}catch(IOException ignored){}
        }catch(InterruptedException cancelled){throw cancelled;}catch(Exception ignored){}finally{NativeEngine.stop();}
        if(ticket!=generation||Thread.currentThread().isInterrupted())throw new InterruptedException();
        Collections.sort(latencies);return new Measurement(latencies.isEmpty()?-1:latencies.get(latencies.size()/2),System.currentTimeMillis(),bandwidth,successes,3);
    }
    static long probe(int port) throws IOException {
        IOException last=null;for(String endpoint:List.of("https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204")){long start=System.nanoTime();HttpsURLConnection connection=(HttpsURLConnection)new URL(endpoint).openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));connection.setConnectTimeout(3500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(false);try{if(connection.getResponseCode()!=204)throw new IOException("HTTPS check failed");return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}catch(IOException failure){last=failure;}finally{connection.disconnect();}}throw last;
    }
    private static long downloadSpeed(int port) throws IOException {HttpsURLConnection connection=(HttpsURLConnection)new URL("https://speed.cloudflare.com/__down?bytes=262144").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));connection.setConnectTimeout(3000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);long start=System.nanoTime(),bytes=0;try{if(connection.getResponseCode()!=200)throw new IOException("Bandwidth check failed");try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[8192];int count;while(bytes<262144&&(count=input.read(buffer,0,(int)Math.min(buffer.length,262144-bytes)))!=-1){bytes+=count;if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(5))break;}}return bytes==262144?(long)(bytes/(Math.max(1,System.nanoTime()-start)/1e9)):0;}finally{connection.disconnect();}}
    static String configuration(Server server,int port,boolean vpn){JsonObject root=new JsonObject();JsonObject log=new JsonObject();log.addProperty("disabled",true);root.add("log",log);JsonArray inbounds=new JsonArray();JsonObject inbound=new JsonObject();if(vpn){inbound.addProperty("type","tun");inbound.addProperty("tag","tun-in");inbound.addProperty("interface_name","moondrop");JsonArray addresses=new JsonArray();addresses.add("172.19.0.1/30");addresses.add("fdfe:dcba:9876::1/126");inbound.add("address",addresses);inbound.addProperty("mtu",9000);inbound.addProperty("auto_route",false);inbound.addProperty("stack","gvisor");}else{inbound.addProperty("type","mixed");inbound.addProperty("listen","127.0.0.1");inbound.addProperty("listen_port",port);}inbounds.add(inbound);root.add("inbounds",inbounds);JsonArray outbounds=new JsonArray();JsonObject outbound=server.outbound();outbound.addProperty("tag","proxy");outbounds.add(outbound);root.add("outbounds",outbounds);JsonObject route=new JsonObject();route.addProperty("final","proxy");route.addProperty("auto_detect_interface",false);if(vpn){JsonObject dns=new JsonObject();JsonArray dnsServers=new JsonArray();JsonObject bootstrap=new JsonObject();bootstrap.addProperty("type","local");bootstrap.addProperty("tag","bootstrap");dnsServers.add(bootstrap);JsonObject dnsServer=new JsonObject();dnsServer.addProperty("type","https");dnsServer.addProperty("tag","encrypted-dns");dnsServer.addProperty("server","1.1.1.1");dnsServer.addProperty("server_port",443);dnsServer.addProperty("path","/dns-query");dnsServer.addProperty("detour","proxy");dnsServers.add(dnsServer);dns.add("servers",dnsServers);dns.addProperty("final","encrypted-dns");route.addProperty("default_domain_resolver","bootstrap");root.add("dns",dns);JsonArray rules=new JsonArray();JsonObject rule=new JsonObject();rule.addProperty("port",53);rule.addProperty("action","hijack-dns");rules.add(rule);route.add("rules",rules);}root.add("route",route);return root.toString();}
    private void persist(){try{repository.save(servers,measurements);}catch(IOException ignored){}}
    public String diagnostics(){AppState current=state();return "Moondrop Android\nState: "+current.title+"\nServers: "+current.servers.size()+"\nCached measurements: "+current.measurements.size()+"\nChecked: "+current.checked+"\nWorking: "+current.working+"\n"+(current.error.isEmpty()?"":current.error);}
}
