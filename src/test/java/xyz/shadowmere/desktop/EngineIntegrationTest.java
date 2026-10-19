package xyz.shadowmere.desktop;

import com.sun.net.httpserver.HttpServer;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class EngineIntegrationTest {
    @TempDir Path directory;
    private int freePort() throws Exception {try(ServerSocket s=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
    private Path core() {
        String core=System.getProperty("engine.test.path","");
        Assumptions.assumeTrue(!core.isEmpty()&&Files.isExecutable(Path.of(core)),"Set -Dengine.test.path for native engine tests");
        return Path.of(core);
    }
    private Server server() throws Exception {
        return new Server("manual:test","127.0.0.1",freePort(),"chacha20-ietf-poly1305","test-only-password","","Local test","",true);
    }
    private Path ownership() throws Exception {
        try(var files=Files.list(directory)) {
            return files.filter(p->p.getFileName().toString().endsWith(".json.owner")).findFirst().orElseThrow();
        }
    }
    private void assertListenerClosed(int port) throws Exception {
        // A refused connection proves the listener closed without treating Windows
        // TIME_WAIT as an active listener or rebinding with unsafe SO_REUSEADDR.
        // Process exit can precede final kernel socket teardown on Windows.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        do {
            try(Socket socket=new Socket()) {
                int timeout=(int)Math.max(1,Math.min(250,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
                try {socket.connect(new InetSocketAddress("127.0.0.1",port),timeout);}
                catch(ConnectException refused) {return;}
                catch(SocketTimeoutException pending) { /* Not proof of closure: retry. */ }
            }
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)break;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(25)));
        } while(System.nanoTime()<deadline);
        fail("Proxy listener still accepts connections or does not refuse them after two seconds on port "+port);
    }
    @Test void encryptedRoundTripAndCleanup() throws Exception {
        String core=System.getProperty("engine.test.path","");
        Assumptions.assumeTrue(!core.isEmpty()&&Files.isExecutable(Path.of(core)),"Set -Dengine.test.path to run the real sing-box integration test");
        int ssPort=freePort(),localPort=freePort();
        HttpServer target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",exchange->{byte[] b="encrypted-round-trip".getBytes();exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});target.start();
        Path remoteConfig=directory.resolve("server.json");
        Files.writeString(remoteConfig,"""
            {"log":{"level":"error"},"inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":%d,
            "method":"chacha20-ietf-poly1305","password":"test-only-password"}],"outbounds":[{"type":"direct"}]}
            """.formatted(ssPort));
        Process remote=new ProcessBuilder(core,"run","-c",remoteConfig.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
            while(System.nanoTime()<deadline) {try(Socket s=new Socket("127.0.0.1",ssPort)){ready=true;break;}catch(Exception e){Thread.sleep(50);}}
            assertTrue(ready,"Local Shadowsocks test server did not start");
            Server server=new Server("manual:test","127.0.0.1",ssPort,"chacha20-ietf-poly1305","test-only-password","","Local test","",true);
            try(ProxyEngine client=new ProxyEngine(Path.of(core),directory)) {
                client.start(Map.of(localPort,server),ignored->{});
                URL url=URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/").toURL();
                HttpURLConnection request=(HttpURLConnection)url.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",localPort)));
                request.setConnectTimeout(4000);request.setReadTimeout(4000);
                try {assertEquals("encrypted-round-trip",new String(request.getInputStream().readAllBytes()));}finally{request.disconnect();}
                assertTrue(client.alive());
            }
            assertListenerClosed(localPort);
            ProxyEngine.ensurePortFree(localPort);
            // A user should be able to reconnect immediately after real traffic.
            try(ProxyEngine next=new ProxyEngine(Path.of(core),directory)) {
                next.start(Map.of(localPort,server),ignored->{});
                assertTrue(next.alive());
            }
            try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("session-")));}
        } finally {remote.destroy();if(!remote.waitFor(2,TimeUnit.SECONDS))remote.destroyForcibly();target.stop(0);}
    }
    @Test void genuineTwoHopRequiresEntryAndExit() throws Exception {
        Path core=core();
        int entryPort=freePort(),exitPort=freePort();
        HttpServer target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",exchange->{byte[] b="two-encrypted-hops".getBytes();exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});
        target.start();
        Process[] remotes=new Process[2];
        try {
            for(int n=0;n<2;n++) {
                int port=n==0?entryPort:exitPort;
                Path config=directory.resolve("hop-"+n+".json");
                Files.writeString(config,"""
                    {"log":{"level":"error"},"inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":%d,
                    "method":"chacha20-ietf-poly1305","password":"test-only-password"}],"outbounds":[{"type":"direct"}]}
                    """.formatted(port));
                remotes[n]=new ProcessBuilder(core.toString(),"run","-c",config.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("hop-"+n+".log").toFile()).start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
                while(System.nanoTime()<deadline) {try(Socket socket=new Socket("127.0.0.1",port)){ready=true;break;}catch(IOException pending){Thread.sleep(50);}}
                assertTrue(ready,"Encrypted hop fixture failed to start");
            }
            Server entry=new Server("manual:entry","127.0.0.1",entryPort,"chacha20-ietf-poly1305","test-only-password","","Entry fixture","",true);
            Server exit=new Server("manual:exit","127.0.0.1",exitPort,"chacha20-ietf-poly1305","test-only-password","","Exit fixture","",true);
            int establishedPort=freePort(),testPort=freePort();
            try(ProxyEngine established=new ProxyEngine(core,directory.resolve("established"))) {
                established.start(Map.of(establishedPort,entry),ignored->{});
                try(ProxyEngine test=new ProxyEngine(core,directory.resolve("candidate-test"))) {
                    test.startViaLocalProxy(testPort,exit,establishedPort,ignored->{});
                    assertEquals("two-encrypted-hops",requestThroughProxy(testPort,target.getAddress().getPort()));
                }
                assertTrue(established.alive(),"Closing a candidate test must retain the established connection");
                assertEquals("two-encrypted-hops",requestThroughProxy(establishedPort,target.getAddress().getPort()));
                try(ProxyEngine test=new ProxyEngine(core,directory.resolve("candidate-no-fallback"))) {
                    test.startViaLocalProxy(testPort,exit,establishedPort,ignored->{});
                    established.close();
                    assertThrows(IOException.class,()->requestThroughProxy(testPort,target.getAddress().getPort()));
                }
            }
            try(ProxyEngine client=new ProxyEngine(core,directory)) {
                client.startChain(entry,exit,ignored->{});
                assertEquals("two-encrypted-hops",requestThroughChain(target.getAddress().getPort()));
                remotes[0].destroyForcibly();remotes[0].waitFor(2,TimeUnit.SECONDS);
                // A new HTTP connection must fail after the entry dies; the live exit cannot be contacted directly.
                assertThrows(IOException.class,()->requestThroughChain(target.getAddress().getPort()));
            }
            assertListenerClosed(ProxyEngine.PORT);
        } finally {
            for(Process remote:remotes) if(remote!=null) {remote.destroyForcibly();remote.waitFor(2,TimeUnit.SECONDS);}
            target.stop(0);
        }
    }
    private String requestThroughChain(int targetPort) throws IOException {return requestThroughProxy(ProxyEngine.PORT,targetPort);}
    private String requestThroughProxy(int proxyPort,int targetPort) throws IOException {
        URL url=URI.create("http://127.0.0.1:"+targetPort+"/").toURL();
        HttpURLConnection request=(HttpURLConnection)url.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",proxyPort)));
        request.setConnectTimeout(3000);request.setReadTimeout(3000);
        try {return new String(request.getInputStream().readAllBytes());}finally{request.disconnect();}
    }
    @Test void occupiedPortProducesActionableError() throws Exception {
        try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {
            assertTrue(assertThrows(java.io.IOException.class,()->ProxyEngine.ensurePortFree(socket.getLocalPort())).getMessage().contains("in use"));
        }
    }
    @Test void portCheckAllowsABriefListenerTeardown() throws Exception {
        try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {
            int port=socket.getLocalPort();
            Thread closer=new Thread(()->{
                try {Thread.sleep(100);socket.close();}
                catch(InterruptedException e) {Thread.currentThread().interrupt();}
                catch(IOException ignored) { }
            });
            closer.start();
            try {assertDoesNotThrow(()->ProxyEngine.ensurePortFree(port));}
            finally {closer.join(1000);}
            assertListenerClosed(port);
        }
    }
    @Test void recoversOnlyAnAppOwnedCoreAndRemovesItsSession() throws Exception {
        String core=System.getProperty("engine.test.path","");
        Assumptions.assumeTrue(!core.isEmpty()&&Files.isExecutable(Path.of(core)));
        int port=freePort();
        Server server=new Server("manual:test","127.0.0.1",freePort(),"chacha20-ietf-poly1305","test-only-password","","Local test","",true);
        try(ProxyEngine engine=new ProxyEngine(Path.of(core),directory)) {
            engine.start(Map.of(port,server),ignored->{});
            assertTrue(engine.alive());
            // Startup owns the instance lock before invoking this recovery path.
            ProxyEngine.recover(Path.of(core),directory);
            assertFalse(engine.alive());assertListenerClosed(port);ProxyEngine.ensurePortFree(port);
            try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("session-")));}
        }
    }
    @Test void ownershipIsPersistedBeforeStartReturnsAndRemovedOnClose() throws Exception {
        Path core=core();
        try(ProxyEngine engine=new ProxyEngine(core,directory)) {
            engine.start(Map.of(freePort(),server()),ignored->{});
            JsonObject record=JsonParser.parseString(Files.readString(ownership())).getAsJsonObject();
            ProcessHandle child=ProcessHandle.of(record.get("pid").getAsLong()).orElseThrow();
            assertEquals(child.info().startInstant().orElseThrow().toString(),record.get("started").getAsString());
            assertEquals(core.toRealPath().toString(),record.get("executable").getAsString());
            assertTrue(Files.isRegularFile(Path.of(record.get("config").getAsString())));
        }
        try(var files=Files.list(directory)) {assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("session-")));}
    }
    @Test void staleStartInstantNeverStopsAnUnrelatedPid() throws Exception {
        Path core=core();
        try(ProxyEngine engine=new ProxyEngine(core,directory)) {
            engine.start(Map.of(freePort(),server()),ignored->{});
            Path recordPath=ownership();
            JsonObject record=JsonParser.parseString(Files.readString(recordPath)).getAsJsonObject();
            record.addProperty("started",java.time.Instant.parse(record.get("started").getAsString()).minusSeconds(1).toString());
            Files.writeString(recordPath,record.toString());
            ProxyEngine.recover(core,directory);
            assertTrue(engine.alive(),"A recycled PID must not authorize termination");
            assertFalse(Files.exists(recordPath),"Stale metadata should be discarded");
        }
    }
    @Test void matchingPidAndTimeWithoutMatchingExecutableNeverStopsProcess() throws Exception {
        Path core=core();
        String javaName=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        Process unrelated=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",javaName).toString(),
            "-cp",System.getProperty("java.class.path"),SleepingProcess.class.getName()).start();
        try(ProxyEngine engine=new ProxyEngine(core,directory)) {
            engine.start(Map.of(freePort(),server()),ignored->{});
            Path recordPath=ownership();
            JsonObject record=JsonParser.parseString(Files.readString(recordPath)).getAsJsonObject();
            record.addProperty("pid",unrelated.pid());
            record.addProperty("started",unrelated.info().startInstant().orElseThrow().toString());
            Files.writeString(recordPath,record.toString());
            ProxyEngine.recover(core,directory);
            assertTrue(unrelated.isAlive(),"PID and timestamp alone must never authorize a process kill");
            assertTrue(engine.alive(),"The unrecorded engine must also remain untouched");
        } finally {unrelated.destroyForcibly();unrelated.waitFor(2,TimeUnit.SECONDS);}
    }
    @Test void invalidSessionPathCannotAuthorizeTerminationOrDeleteOutsideDataDirectory() throws Exception {
        Path core=core();
        Path outside=Files.createTempFile("moondrop-foreign-", ".json");
        Files.writeString(outside,"keep");
        try(ProxyEngine engine=new ProxyEngine(core,directory)) {
            engine.start(Map.of(freePort(),server()),ignored->{});
            Path recordPath=ownership();
            JsonObject record=JsonParser.parseString(Files.readString(recordPath)).getAsJsonObject();
            record.addProperty("config",outside.toString());
            Files.writeString(recordPath,record.toString());
            assertThrows(IOException.class,()->ProxyEngine.recover(core,directory));
            assertTrue(engine.alive());
            assertEquals("keep",Files.readString(outside));
        } finally {Files.deleteIfExists(outside);}
    }
    @Test void recoveryLeavesOtherDataDirectoryCoreRunning() throws Exception {
        Path core=core();
        try(ProxyEngine owned=new ProxyEngine(core,directory);ProxyEngine other=new ProxyEngine(core,directory.resolve("other"))) {
            owned.start(Map.of(freePort(),server()),ignored->{});
            other.start(Map.of(freePort(),server()),ignored->{});
            ProxyEngine.recover(core,directory);
            assertFalse(owned.alive());
            assertTrue(other.alive(),"Never scan or stop another instance's engine");
        }
    }
    public static final class SleepingProcess {
        public static void main(String[] args) throws Exception {Thread.sleep(60_000);}
    }
}
