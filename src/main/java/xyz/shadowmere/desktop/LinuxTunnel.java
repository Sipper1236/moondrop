package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Explicit Linux full-device mode. Privilege is confined to an installed, bounded broker. */
public final class LinuxTunnel implements AutoCloseable {
    private static final Path HELPER=Path.of("/usr/local/libexec/moondrop-tunnel-helper.py");
    private static final Path STATUS=Path.of("/run/moondrop-tunnel/status.json");
    private final Path data;
    private final Consumer<String> log;
    private Process helper;
    private Writer commands;
    private final BlockingQueue<JsonObject> responses=new LinkedBlockingQueue<>();
    public record Status(boolean active,boolean blocked,String message) { }
    public LinuxTunnel(Path data,Consumer<String> log) { this.data=data;this.log=log; }
    public static boolean supported() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch")) && Files.exists(Path.of("/dev/net/tun")) && executable("pkexec")!=null && executable("nft")!=null && executable("ip")!=null; }
    public static Status status() {
        try {
            if(!Files.isRegularFile(STATUS,LinkOption.NOFOLLOW_LINKS)||Files.size(STATUS)>4096)return new Status(false,false,"Application Proxy Mode");
            if(!"root".equals(Files.getOwner(STATUS).getName()))return new Status(false,false,"Untrusted tunnel status");
            JsonObject state=JsonParser.parseString(Files.readString(STATUS)).getAsJsonObject();
            return new Status(state.get("active").getAsBoolean(),state.get("blocked").getAsBoolean(),state.get("message").getAsString());
        } catch(Exception ignored) { return new Status(false,false,"Tunnel status unavailable"); }
    }
    public synchronized void start(Server entry,Server exit,boolean strict) throws Exception {
        if(!supported())throw new IOException("Full-device mode requires Linux x64, nftables, iproute2, PolicyKit and /dev/net/tun.");
        if(helper!=null&&helper.isAlive())throw new IOException("Disconnect the current full-device tunnel first.");
        boolean blocked=status().blocked();
        JsonObject request=new JsonObject();request.addProperty("op","start");request.addProperty("strict",strict);
        // Resolve and persist before privilege or firewall changes; while blocked, never invoke system DNS.
        request.add("exit",pinned(exit,blocked));if(entry!=null)request.add("entry",pinned(entry,blocked));
        installHelper();responses.clear();
        helper=new ProcessBuilder(executable("pkexec"),"/usr/bin/python3",HELPER.toString(),"--serve").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        commands=new OutputStreamWriter(helper.getOutputStream(),StandardCharsets.UTF_8);
        Process owned=helper;
        Thread reader=new Thread(()->{
            try(BufferedReader in=owned.inputReader(StandardCharsets.UTF_8)) {
                String line;while((line=in.readLine())!=null) { if(line.length()>8192)continue;try {responses.offer(JsonParser.parseString(line).getAsJsonObject());}catch(RuntimeException invalid){} }
            }catch(IOException ignored){}finally {JsonObject ended=new JsonObject();ended.addProperty("error","The privileged tunnel stopped. Check Settings to reconnect or restore direct internet.");responses.offer(ended);}
        },"linux-tunnel-status");reader.setDaemon(true);reader.start();
        commands.write(request.toString()+"\n");commands.flush();
        JsonObject response=responses.poll(180,TimeUnit.SECONDS);
        if(response==null) { stop();throw new IOException("Tunnel setup timed out. Strict protection may still be active; check Settings."); }
        if(response.has("error")) { stop();throw new IOException(response.get("error").getAsString()); }
        if(!response.has("active")||!response.get("active").getAsBoolean()) {stop();throw new IOException("The full-device tunnel did not become ready.");}
        log.accept("Full-device tunnel active"+(strict?" · strict protection enabled":""));
    }
    public synchronized void stop() {
        if(helper!=null) {
            try {if(commands!=null){commands.write("{\"op\":\"stop\"}\n");commands.flush();commands.close();}}
            catch(IOException ignored){}
            try {if(!helper.waitFor(10,TimeUnit.SECONDS)){helper.destroy();helper.waitFor(3,TimeUnit.SECONDS);}}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            helper=null;commands=null;
        }
        if(status().blocked())log.accept("Disconnected · direct internet remains blocked until you restore it in Settings");
    }
    public synchronized void release() throws Exception {
        stop();
        if(!Files.isRegularFile(HELPER,LinkOption.NOFOLLOW_LINKS)||!"root".equals(Files.getOwner(HELPER).getName()))throw new IOException("The trusted tunnel helper is not installed.");
        Process release=new ProcessBuilder(executable("pkexec"),"/usr/bin/python3",HELPER.toString(),"--release").redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!release.waitFor(60,TimeUnit.SECONDS)||release.exitValue()!=0)throw new IOException("Direct internet could not be restored. Run the installed helper with --release using administrator authorization.");
        log.accept("Direct internet restored");
    }
    public synchronized boolean isAlive() { return helper!=null&&helper.isAlive()&&status().active(); }
    @Override public void close(){stop();}
    private JsonObject pinned(Server server,boolean blocked) throws Exception {
        return new TunnelEndpoints(data).outbound(server,blocked);
    }
    private void installHelper() throws Exception {
        byte[] bundled;
        try(InputStream resource=LinuxTunnel.class.getResourceAsStream("/META-INF/moondrop/scripts/linux-tunnel-helper.py")) {if(resource==null)throw new IOException("This build is missing its Linux tunnel helper.");bundled=resource.readAllBytes();}
        if(Files.isRegularFile(HELPER,LinkOption.NOFOLLOW_LINKS)&&"root".equals(Files.getOwner(HELPER).getName())&&Arrays.equals(bundled,Files.readAllBytes(HELPER)))return;
        Files.createDirectories(data);
        Path source=Files.createTempFile(data,"tunnel-helper-",".py");
        try {
            Files.write(source,bundled);
            Process mkdir=new ProcessBuilder(executable("pkexec"),"/usr/bin/install","-d","-o","root","-g","root","-m","0755",HELPER.getParent().toString()).inheritIO().start();
            if(!mkdir.waitFor(60,TimeUnit.SECONDS)||mkdir.exitValue()!=0)throw new IOException("Tunnel helper installation was cancelled.");
            Process install=new ProcessBuilder(executable("pkexec"),"/usr/bin/install","-o","root","-g","root","-m","0755",source.toString(),HELPER.toString()).inheritIO().start();
            if(!install.waitFor(60,TimeUnit.SECONDS)||install.exitValue()!=0)throw new IOException("Tunnel helper installation was cancelled.");
        }finally{Files.deleteIfExists(source);}
    }
    private static String executable(String name) {for(String dir:List.of("/usr/bin","/usr/sbin","/bin","/sbin")){Path path=Path.of(dir,name);if(Files.isExecutable(path))return path.toString();}return null;}
}
