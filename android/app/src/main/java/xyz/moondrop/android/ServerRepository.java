package xyz.moondrop.android;

import android.content.Context;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import xyz.shadowmere.desktop.ProxyLinks;
import xyz.shadowmere.desktop.Server;

/** Private, atomic cache of connection profiles and locally measured results. */
public final class ServerRepository {
    private static final int MAX_BYTES=8*1024*1024;
    private static final Set<String> CIPHERS=Set.of("2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305","aes-128-gcm","aes-192-gcm","aes-256-gcm","chacha20-ietf-poly1305","xchacha20-ietf-poly1305","aes-128-ctr","aes-192-ctr","aes-256-ctr","aes-128-cfb","aes-192-cfb","aes-256-cfb","rc4-md5","chacha20-ietf","xchacha20");
    private static final Map<Server,String> KEYS=Collections.synchronizedMap(new WeakHashMap<>());
    private final File cache;
    private final Gson gson=new Gson();
    public record Snapshot(List<Server> servers,Map<String,Measurement> measurements) { }
    public ServerRepository(Context context) {cache=new File(context.getFilesDir(),"servers-v1.json");}
    ServerRepository(File file) {cache=file;}
    public static String key(Server server) {
        String existing=KEYS.get(server);if(existing!=null)return existing;
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(server.outbound().toString().getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);for(byte value:digest)hex.append(Character.forDigit((value >>> 4)&15,16)).append(Character.forDigit(value&15,16));String result=hex.toString();KEYS.put(server,result);return result;}
        catch(Exception failure) {throw new IllegalArgumentException("Invalid protected server");}
    }
    public synchronized Snapshot load() {
        try(Reader reader=new InputStreamReader(new FileInputStream(cache),StandardCharsets.UTF_8)) {
            JsonObject root=JsonParser.parseReader(reader).getAsJsonObject(); List<Server> servers=new ArrayList<>();
            for(JsonElement row:root.getAsJsonArray("servers"))try {Server server=gson.fromJson(row,Server.class);if(valid(server))servers.add(server);}catch(RuntimeException ignored){}
            Map<String,Measurement> measurements=new HashMap<>();JsonObject values=root.getAsJsonObject("measurements");
            for(Server server:servers) {String key=key(server);if(values.has(key))try {JsonObject value=values.getAsJsonObject(key);Measurement measurement=new Measurement(value.get("latencyMs").getAsLong(),value.get("checkedAt").getAsLong(),value.get("bytesPerSecond").getAsLong(),value.get("successes").getAsInt(),value.get("attempts").getAsInt()); if(measurement.attempts>0&&measurement.successes>=0&&measurement.successes<=measurement.attempts)measurements.put(key,measurement);}catch(RuntimeException ignored){}}
            return new Snapshot(List.copyOf(servers),Map.copyOf(measurements));
        }catch(Exception ignored) {return new Snapshot(List.of(),Map.of());}
    }
    public synchronized void save(List<Server> servers,Map<String,Measurement> measurements) throws IOException {
        JsonObject root=new JsonObject();root.add("servers",gson.toJsonTree(servers));root.add("measurements",gson.toJsonTree(measurements));
        File temp=new File(cache.getParentFile(),cache.getName()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temp)) {output.write(gson.toJson(root).getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        if(!temp.renameTo(cache)) {temp.delete();throw new IOException("Could not save server cache");}
    }
    public List<Server> fetch() throws Exception {
        List<Callable<List<Server>>> sources=new ArrayList<>();sources.add(this::shadowmere);
        sources.add(()->parseFeed(download("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt"),"gfp:"));
        sources.add(()->parseFeed(download("https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/ss"),"collector:"));
        sources.add(()->parseFeed(download("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/shadowsocks.txt"),"morpheus:"));
        for(String protocol:List.of("hy2","tuic"))sources.add(()->parseFeed(download("https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/"+protocol+".txt"),"gfp:"));
        sources.add(()->parseFeed(download("https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/mix"),"collector:"));
        for(String protocol:List.of("vless","trojan","hysteria2","tuic"))sources.add(()->parseFeed(download("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/"+protocol+".txt"),"morpheus:"));
        ExecutorService workers=Executors.newFixedThreadPool(6);
        try {Map<String,Server> unique=new LinkedHashMap<>();for(Future<List<Server>> result:workers.invokeAll(sources,45,TimeUnit.SECONDS))try {for(Server server:result.get())unique.putIfAbsent(key(server),server);}catch(ExecutionException|CancellationException ignored){} if(unique.isEmpty())throw new IOException("Server sources are unavailable. Your saved servers are still available.");return List.copyOf(unique.values());}
        finally {workers.shutdownNow();}
    }
    private List<Server> shadowmere() throws Exception {
        List<Server> result=new ArrayList<>();int pages=1;
        for(int page=1;page<=pages;page++) {
            JsonElement root=JsonParser.parseString(download("https://shadowmere.xyz/api/proxies/?is_active=true&page_size=200&page="+page));
            JsonArray rows;if(root.isJsonArray())rows=root.getAsJsonArray();else {JsonObject object=root.getAsJsonObject();rows=object.getAsJsonArray("results");if(object.has("total_pages"))pages=Math.min(100,object.get("total_pages").getAsInt());else if(object.has("next")&&!object.get("next").isJsonNull())pages=Math.min(100,page+1);}
            for(JsonElement item:rows)try {JsonObject row=item.getAsJsonObject();if(row.has("is_active")&&!row.get("is_active").getAsBoolean())continue;Server server=ProxyLinks.parse(text(row,"id"),text(row,"url"),text(row,"location_country_code"),"",text(row,"last_checked"),true);if(valid(server))result.add(server);}catch(RuntimeException ignored){}
            if(page<pages)Thread.sleep(400);
        }return result;
    }
    static List<Server> parseFeed(String source,String prefix) {
        Map<String,Server> result=new LinkedHashMap<>();
        for(String line:source.split("\\R"))try {
            line=line.strip();if(line.isEmpty()||line.startsWith("#"))continue;
            Server parsed=ProxyLinks.parse("",line,"","","",true);if(!valid(parsed))continue;
            String country="";int fragment=line.indexOf('#');if(fragment>=0) {String label=URLDecoder.decode(line.substring(fragment+1),StandardCharsets.UTF_8);int[] points=label.codePoints().toArray();for(int i=0;i+1<points.length;i++)if(points[i]>=0x1F1E6&&points[i]<=0x1F1FF&&points[i+1]>=0x1F1E6&&points[i+1]<=0x1F1FF){country=""+(char)('A'+points[i]-0x1F1E6)+(char)('A'+points[i+1]-0x1F1E6);break;}}
            String id=key(parsed);result.putIfAbsent(id,new Server(prefix+id,parsed.host(),parsed.port(),parsed.method(),parsed.password(),country,"","",true,parsed.protocol(),parsed.profileJson()));
        }catch(RuntimeException ignored){}return List.copyOf(result.values());
    }
    static boolean valid(Server server) {
        if(server==null||!Preferences.PROTOCOLS.contains(server.protocol()))return false;
        if(server.protocol().equals("shadowsocks")) {
            if(!CIPHERS.contains(server.method()))return false;
            if(server.method().startsWith("2022-"))try {int length=server.method().equals("2022-blake3-aes-128-gcm")?16:32;for(String part:server.password().split(":",-1))if(Base64.getDecoder().decode(part).length!=length)return false;}catch(RuntimeException invalid){return false;}
        }
        try {server.outbound();return true;}catch(RuntimeException invalid){return false;}
    }
    static String download(String address) throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(15000);connection.setRequestProperty("User-Agent","Moondrop-Android");connection.setInstanceFollowRedirects(false);
        try {if(connection.getResponseCode()!=200)throw new IOException("Server source unavailable");try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();if(out.size()+count>MAX_BYTES)throw new IOException("Server source too large");out.write(buffer,0,count);}return out.toString(StandardCharsets.UTF_8.name());}}finally {connection.disconnect();}
    }
    private static String text(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
}
