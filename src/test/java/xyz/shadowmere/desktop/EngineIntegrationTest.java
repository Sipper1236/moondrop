package xyz.shadowmere.desktop;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EngineIntegrationTest {
    @TempDir Path directory;
    private int freePort() throws Exception {try(ServerSocket s=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
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
        Process remote=new ProcessBuilder(core,"run","-c",remoteConfig.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("remote.log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
            while(System.nanoTime()<deadline) {try(Socket s=new Socket("127.0.0.1",ssPort)){ready=true;break;}catch(Exception e){Thread.sleep(50);}}
            assertTrue(ready,"Local Shadowsocks test server did not start");
            Server server=new Server("test","127.0.0.1",ssPort,"chacha20-ietf-poly1305","test-only-password","","Local test","",true);
            try(ProxyEngine client=new ProxyEngine(Path.of(core),directory)) {
                client.start(Map.of(localPort,server),ignored->{});
                URL url=URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/").toURL();
                HttpURLConnection request=(HttpURLConnection)url.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",localPort)));
                request.setConnectTimeout(4000);request.setReadTimeout(4000);
                try {assertEquals("encrypted-round-trip",new String(request.getInputStream().readAllBytes()));}finally{request.disconnect();}
                assertTrue(client.alive());
            }
            ProxyEngine.ensurePortFree(localPort);
            try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("session-")));}
        } finally {remote.destroy();if(!remote.waitFor(2,TimeUnit.SECONDS))remote.destroyForcibly();target.stop(0);}
    }
    @Test void occupiedPortProducesActionableError() throws Exception {
        try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {
            assertTrue(assertThrows(java.io.IOException.class,()->ProxyEngine.ensurePortFree(socket.getLocalPort())).getMessage().contains("in use"));
        }
    }
    @Test void recoversOnlyAnAppOwnedCoreAndRemovesItsSession() throws Exception {
        String core=System.getProperty("engine.test.path","");
        Assumptions.assumeTrue(!core.isEmpty()&&Files.isExecutable(Path.of(core)));
        int port=freePort();
        Server server=new Server("test","127.0.0.1",freePort(),"chacha20-ietf-poly1305","test-only-password","","Local test","",true);
        try(ProxyEngine engine=new ProxyEngine(Path.of(core),directory)) {
            engine.start(Map.of(port,server),ignored->{});
            assertTrue(engine.alive());
            // Startup owns the instance lock before invoking this recovery path.
            ProxyEngine.recover(Path.of(core),directory);
            assertFalse(engine.alive());ProxyEngine.ensurePortFree(port);
            try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("session-")));}
        }
    }
}
