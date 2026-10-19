package xyz.shadowmere.desktop;

import com.google.gson.*;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Windows UAC/macOS Authorization bridge to fixed, narrow, privileged helpers. */
final class NativeDesktopTunnel {
    private final Path data;
    private final Consumer<String> log;
    private final TunnelEndpoints endpoints;
    private Path session;
    private long brokerPid;
    private boolean started,macLaunched;
    private static volatile Path trustedWindowsStore;
    NativeDesktopTunnel(Path data,Consumer<String> log){this.data=data;this.log=log;endpoints=new TunnelEndpoints(data);}
    private static boolean windows(){return DesktopTunnel.platform().equals("Windows");}
    static boolean supported(){
        try {
            String platform=CoreInstaller.platform(System.getProperty("os.name"),System.getProperty("os.arch"));
            return switch(platform){case "windows-amd64","windows-arm64" -> Files.isRegularFile(powershell());case "darwin-amd64","darwin-arm64" -> macPython()!=null;default -> false;};
        }catch(RuntimeException|LinkageError unavailable){return false;}
    }
    static Path store(){return windows()?Path.of(WindowsPaths.data,"Moondrop","Tunnel"):Path.of("/var/run/moondrop-tunnel");}
    private static Path helper(){return windows()?store().resolve("windows-tunnel-helper.ps1"):Path.of("/Library/PrivilegedHelperTools/xyz.moondrop.tunnel.py");}
    private static Path powershell(){return Path.of(WindowsPaths.system,"WindowsPowerShell","v1.0","powershell.exe");}
    private static final class WindowsPaths {
        static final String data=Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_ProgramData);
        static final String system=Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_System);
    }
    private static boolean privateRootPath(Path path){
        try {
            Path actual=path.toRealPath();
            for(Path current=actual;current!=null;current=current.getParent()) {
                if(!"root".equals(Files.getOwner(current,LinkOption.NOFOLLOW_LINKS).getName()))return false;
                Set<PosixFilePermission> p=Files.getPosixFilePermissions(current,LinkOption.NOFOLLOW_LINKS);
                if(p.contains(PosixFilePermission.GROUP_WRITE)||p.contains(PosixFilePermission.OTHERS_WRITE))return false;
            }
            return true;
        }catch(IOException|UnsupportedOperationException e){return false;}
    }
    private static Path macPython(){
        for(String name:List.of("/Library/Developer/CommandLineTools/usr/bin/python3","/Library/Frameworks/Python.framework/Versions/Current/bin/python3","/usr/bin/python3")) {
            Path path=Path.of(name);if(Files.isExecutable(path)&&privateRootPath(path))try{return path.toRealPath();}catch(IOException ignored){}
        }
        return null;
    }
    static DesktopTunnel.Status status(){
        try {
        JsonObject value=readStatus(store().resolve("status.json"),true);
        if(value==null)return new DesktopTunnel.Status(false,false,"Application Proxy Mode");
        boolean active=flag(value,"active"),blocked=flag(value,"blocked");
        if(windows()&&active&&!liveWindowsEngine(value)){active=false;blocked=flag(value,"strict");}
        if(!windows()&&active&&(!value.has("brokerPid")||!ProcessHandle.of(value.get("brokerPid").getAsLong()).map(ProcessHandle::isAlive).orElse(false))){active=false;blocked=flag(value,"strict");}
        return new DesktopTunnel.Status(active,blocked,text(value,"message"));
        }catch(RuntimeException|LinkageError unavailable){return new DesktopTunnel.Status(false,false,"Tunnel status unavailable; use platform recovery instructions if strict blocking was active.");}
    }
    static boolean liveWindowsEngine(JsonObject value){
        try {
            long pid=value.get("pid").getAsLong();var process=ProcessHandle.of(pid);
            if(process.isEmpty()||!process.get().isAlive())return false;
            var expected=java.time.Instant.parse(value.get("started").getAsString());
            return process.get().info().startInstant().map(actual->Math.abs(actual.toEpochMilli()-expected.toEpochMilli())<10).orElse(false);
        }catch(RuntimeException malformed){return false;}
    }
    private static boolean flag(JsonObject v,String key){return v.has(key)&&v.get(key).getAsBoolean();}
    private static String text(JsonObject v,String key){return v.has(key)&&!v.get(key).isJsonNull()?v.get(key).getAsString():"";}
    private static JsonObject readStatus(Path path,boolean rootStore){
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>8192)return null;
            if(windows()){if(rootStore&&!windowsStoreTrusted())return null;}
            else if(!"root".equals(Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName())||(rootStore&&!privateRootPath(path)))return null;
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        }catch(IOException|RuntimeException e){return null;}
    }
    private static boolean windowsStoreTrusted(){
        Path directory=store();if(directory.equals(trustedWindowsStore))return true;
        // Compare SIDs, not localized Windows account names. Only SYSTEM/Administrators may write.
        String command="$env:PSModulePath=Join-Path $PSHOME 'Modules';$ErrorActionPreference='Stop';foreach($p in @("+psQuote(directory.toString())+","+psQuote(directory.resolve("status.json").toString())+")){ $a=Get-Acl -LiteralPath $p;$o=$a.GetOwner([System.Security.Principal.SecurityIdentifier]).Value;if($o -notin @('S-1-5-18','S-1-5-32-544')){exit 1};foreach($r in $a.Access){if($r.AccessControlType -eq 'Allow' -and (($r.FileSystemRights -band 0x000D0156) -ne 0) -and $r.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -notin @('S-1-5-18','S-1-5-32-544')){exit 1}}};exit 0";
        try {Process process=powerShell(command).start();boolean done=process.waitFor(10,TimeUnit.SECONDS);if(!done){process.destroyForcibly();return false;}if(process.exitValue()!=0)return false;trustedWindowsStore=directory;return true;}
        catch(IOException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return false;}
    }
    synchronized void start(Server entry,Server exit,boolean strict)throws Exception {
        if(!supported())throw new IOException(DesktopTunnel.requirements());
        if(started)throw new IOException("Disconnect the current full-device tunnel first.");
        Files.createDirectories(data);
        session=Files.createTempDirectory(data,"tunnel-session-");
        try{Files.setPosixFilePermissions(session,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        try {
            Path marker=session.resolve("alive");Files.writeString(marker,"active");
            JsonObject request=new JsonObject();String id=UUID.randomUUID().toString();request.addProperty("requestId",id);request.addProperty("strict",strict);
            boolean blocked=status().blocked();request.add("exit",endpoints.outbound(exit,blocked));if(entry!=null)request.add("entry",endpoints.outbound(entry,blocked));
            log.accept("Full-device tunnel · administrator approval is required for "+DesktopTunnel.platform()+" routing.");
            if(windows()) {
                request.addProperty("parentPid",ProcessHandle.current().pid());request.addProperty("sessionPath",marker.toAbsolutePath().toString());
                writeRequest(request);
                byte[] bundled=resource();installWindows(bundled);
                windowsAction("Connect",session.resolve("request.json"));
                JsonObject response=readStatus(store().resolve("status.json"),true);
                requireActive(response,id);
            }else {
                request.addProperty("op","start");writeRequest(request);
                Path python=macPython();
                run(new ProcessBuilder(python.toString(),"-I","-S","-c","import sys,ssl; assert sys.version_info >= (3,9)"),Duration.ofSeconds(20));
                installAndStartMac(resource(),python);macLaunched=true;
                JsonObject response=await(id,Duration.ofMinutes(4));requireActive(response,id);
                brokerPid=response.has("brokerPid")?response.get("brokerPid").getAsLong():0;
            }
            started=true;log.accept("Full-device tunnel active"+(strict?" · strict disconnect blocking":""));
        }catch(Exception failure){stop();throw failure;}
    }
    private static void requireActive(JsonObject response,String id)throws IOException {
        if(response==null||!id.equals(text(response,"requestId")))throw new IOException("The privileged tunnel did not confirm this request. Existing strict blocking is retained.");
        if(response.has("error")||!flag(response,"active"))throw new IOException(response.has("error")?text(response,"error"):"The full-device tunnel did not become ready.");
    }
    private void writeRequest(JsonObject value)throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IOException("Tunnel request exceeds the helper limit.");
        Path temp=Files.createTempFile(session,"request-",".tmp");
        try{try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}Files.write(temp,bytes);Files.move(temp,session.resolve("request.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    private JsonObject await(String id,Duration timeout)throws Exception {
        long deadline=System.nanoTime()+timeout.toNanos();
        while(System.nanoTime()<deadline){if(Thread.currentThread().isInterrupted())throw new InterruptedException();JsonObject value=readStatus(session.resolve("status.json"),false);if(value!=null&&id.equals(text(value,"requestId")))return value;Thread.sleep(100);}
        throw new IOException("Tunnel setup timed out. Reconnect or use Restore direct internet; strict blocking may remain active.");
    }
    synchronized void stop(){
        boolean interrupted=Thread.interrupted();
        try {
            if(session!=null) {
                if(windows()) {Files.deleteIfExists(session.resolve("alive"));if(started)windowsAction("Disconnect",null);}
                else if(macLaunched) {
                    JsonObject stop=new JsonObject();String id=UUID.randomUUID().toString();stop.addProperty("op","stop");stop.addProperty("requestId",id);writeRequest(stop);
                    try{JsonObject ack=await(id,Duration.ofSeconds(15));if(ack.has("brokerPid"))brokerPid=ack.get("brokerPid").getAsLong();}catch(Exception notStarted){log.accept("Tunnel stop confirmation unavailable. Existing strict protection is retained.");}
                    if(brokerPid>0){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(ProcessHandle.of(brokerPid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<end)Thread.sleep(100);}
                }
            }
        }catch(Exception failure){log.accept("Tunnel cleanup needs attention. Use Restore direct internet in Settings to recover.");}
        finally {started=false;macLaunched=false;if(session!=null){try{Files.deleteIfExists(session.resolve("alive"));}catch(IOException ignored){}try(var files=Files.list(session)){for(Path file:files.toList())Files.deleteIfExists(file);}catch(IOException ignored){}try{Files.deleteIfExists(session);}catch(IOException ignored){}session=null;}if(interrupted)Thread.currentThread().interrupt();}
    }
    synchronized void release()throws Exception {
        stop();
        if(windows())windowsAction("Release",null);
        else {
            Path python=macPython();if(python==null)throw new IOException(DesktopTunnel.requirements());
            Files.createDirectories(data);session=Files.createTempDirectory(data,"tunnel-recovery-");
            try {authorizeMac(macInstallCommand(resource())+"; "+macCommand(python)+" --release");}
            finally {stop();}
        }
        if(status().blocked())throw new IOException("Strict blocking could not be released. Use the platform recovery instructions.");
        log.accept("Direct internet restored");
    }
    boolean isAlive(){if(!started||!status().active())return false;try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",ProxyEngine.PORT),350);return true;}catch(IOException failure){return false;}}
    private byte[] resource()throws IOException {String name=windows()?"windows-tunnel-helper.ps1":"macos-tunnel-helper.py";try(InputStream input=getClass().getResourceAsStream("/META-INF/moondrop/scripts/"+name)){if(input==null)throw new IOException("This build is missing its "+DesktopTunnel.platform()+" tunnel helper.");return input.readAllBytes();}}
    private void installWindows(byte[] bytes)throws Exception {
        Path source=Files.createTempFile(session,"helper-",".ps1");Files.write(source,bytes);
        String digest=sha256(bytes);
        String command=verifiedWindowsScript(source,digest)+" -Action Install -ExpectedHash "+psQuote(digest)+" -VerifiedSource $bytes;if(-not $?){exit 1}";
        elevateWindows(command);
    }
    private void windowsAction(String action,Path request)throws Exception {
        String digest=sha256(resource());
        String command=verifiedWindowsScript(helper(),digest)+" -Action "+action+(request==null?"":" -RequestPath "+psQuote(request.toString()))+";if(-not $?){exit 1}";
        elevateWindows(command);
    }
    static String verifiedWindowsScript(Path source,String digest){
        if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid helper digest");
        // Execute the verified in-memory snapshot; never hash then execute a mutable path.
        return "$env:PSModulePath=Join-Path $PSHOME 'Modules';$ErrorActionPreference='Stop';$bytes=[IO.File]::ReadAllBytes("+psQuote(source.toString())+");$sha=[Security.Cryptography.SHA256]::Create();try{$hash=([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()};if($hash -ne "+psQuote(digest)+"){throw 'Helper integrity failed'};& ([scriptblock]::Create([Text.Encoding]::UTF8.GetString($bytes)))";
    }
    private void elevateWindows(String command)throws Exception {
        String args="-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "+encodePs(command);
        run(powerShell("$ErrorActionPreference='Stop';$p=Start-Process -FilePath "+psQuote(powershell().toString())+" -ArgumentList "+psQuote(args)+" -Verb RunAs -Wait -PassThru;exit $p.ExitCode"),Duration.ofMinutes(5));
    }
    static String psQuote(String value){return "'"+value.replace("'","''")+"'";}
    private static String encodePs(String command){return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));}
    private static ProcessBuilder powerShell(String command){return new ProcessBuilder(powershell().toString(),"-NoProfile","-NonInteractive","-EncodedCommand",encodePs(command)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);}
    static String shQuote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    static String appleQuote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String verifyMac(Path file,String digest){return "test \"$(/usr/bin/shasum -a 256 "+shQuote(file.toString())+" | /usr/bin/cut -d ' ' -f 1)\" = "+shQuote(digest);}
    private String macCommand(Path python){return "/usr/bin/env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin HOME=/var/root "+shQuote(python.toString())+" -I -S "+shQuote(helper().toString());}
    private String macInstallCommand(byte[] bytes)throws Exception {
        Path parent=helper().getParent();
        if(!privateRootPath(Path.of("/Library"))||(Files.exists(parent,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)||!privateRootPath(parent))))throw new IOException("The privileged helper installation directory is unsafe.");
        Path source=Files.createTempFile(session,"helper-",".py");Files.write(source,bytes);String digest=sha256(bytes);
        String target=shQuote(helper().toString()),temp=shQuote(helper()+".new");
        return "set -e; "+verifyMac(source,digest)+"; /usr/bin/install -d -o root -g wheel -m 0755 /Library/PrivilegedHelperTools; /usr/bin/install -o root -g wheel -m 0755 "+shQuote(source.toString())+" "+temp+"; "+verifyMac(Path.of(helper()+".new"),digest)+"; /bin/mv -f "+temp+" "+target;
    }
    private void installAndStartMac(byte[] bytes,Path python)throws Exception {
        long uid=((Number)Files.getAttribute(session,"unix:uid")).longValue();
        String command=macInstallCommand(bytes)+"; "+macCommand(python)+" --serve "+shQuote(session.toString())+" "+uid+" "+ProcessHandle.current().pid()+" >/dev/null 2>&1 & broker_pid=$!; /bin/sleep 0.3; /bin/kill -0 $broker_pid";
        authorizeMac(command);
    }
    private void authorizeMac(String command)throws Exception {run(new ProcessBuilder("/usr/bin/osascript","-e","do shell script "+appleQuote(command)+" with administrator privileges").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),Duration.ofMinutes(5));}
    private static void run(ProcessBuilder builder,Duration timeout)throws Exception {
        Process process=builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long deadline=System.nanoTime()+timeout.toNanos();
        try {while(!process.waitFor(200,TimeUnit.MILLISECONDS)){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(System.nanoTime()>deadline)throw new IOException("Administrator operation timed out; existing strict blocking is retained.");}if(process.exitValue()!=0)throw new IOException("The administrator operation was cancelled or failed. Check the tunnel requirements and try again.");}
        finally{if(process.isAlive())process.destroy();}
    }
}
