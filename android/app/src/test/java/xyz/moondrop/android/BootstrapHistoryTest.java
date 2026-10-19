package xyz.moondrop.android;

import org.junit.Test;
import java.util.*;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;

public class BootstrapHistoryTest {
    private static Server server(String id){return new Server(id,"example.org",443,"aes-256-gcm",id,"US","","",true);}
    @Test public void actualConnectionSuccessOutranksUnprovenFastBenchmark(){
        Server fast=server("fast"),entry=server("entry");BootstrapHistory history=new BootstrapHistory("{}");
        history.connected(entry,100);assertEquals(entry,history.rank(List.of(fast,entry)).get(0));
        BootstrapHistory restored=new BootstrapHistory(history.json());assertEquals(entry,restored.rank(List.of(fast,entry)).get(0));
        assertFalse(history.json().contains("example.org"));assertFalse(history.json().contains("entry"));
    }
    @Test public void refreshPreservesProvenFallbackEvenIfMissingFromFeed(){
        Server saved=server("saved"),fresh=server("fresh"),unproven=server("unproven");BootstrapHistory history=new BootstrapHistory("{}");history.connected(saved,100);
        assertEquals(List.of(fresh,saved),history.retain(List.of(fresh),List.of(saved,unproven)));
        history.failed(saved);assertEquals(List.of(fresh,saved),history.retain(List.of(fresh),List.of(saved)));
        assertFalse(history.contains(saved));assertEquals(fresh,history.rank(List.of(fresh,saved)).get(0));
        assertTrue(new BootstrapHistory(history.json()).contains(saved));
    }
    @Test public void fallbackHistoryIsBoundedAndMostRecentFirst(){
        BootstrapHistory history=new BootstrapHistory("{}");List<Server> candidates=new ArrayList<>();
        for(int i=0;i<30;i++){Server server=server("server"+i);candidates.add(server);history.connected(server,i+1);}
        assertEquals(candidates.get(29),history.rank(candidates).get(0));assertEquals(BootstrapHistory.LIMIT,history.retain(List.of(),candidates).size());
        history.clear();assertTrue(history.retain(List.of(),candidates).isEmpty());
    }
    @Test public void protectedFeedDownloadUsesExplicitProxyWithoutResolvingBlockedFeed() throws Exception {
        try(java.net.ServerSocket listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
            listener.setSoTimeout(3000);
            java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var request=worker.submit(()->{try(var socket=listener.accept()){
                    socket.setSoTimeout(3000);String line=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));return line;
                }});
                java.net.Proxy route=new java.net.Proxy(java.net.Proxy.Type.HTTP,new java.net.InetSocketAddress("127.0.0.1",listener.getLocalPort()));
                assertThrows(java.io.IOException.class,()->ServerRepository.download(route,"https://blocked-feed.invalid/list"));
                assertEquals("CONNECT blocked-feed.invalid:443 HTTP/1.1",request.get(5,java.util.concurrent.TimeUnit.SECONDS));
            }finally{worker.shutdownNow();}
        }
    }
    @Test public void deviceVpnRefreshInboundSharesTheProtectedFinalRoute(){
        var config=com.google.gson.JsonParser.parseString(AppController.configuration(server("entry"),19419,true)).getAsJsonObject();
        var inbounds=config.getAsJsonArray("inbounds");assertEquals(2,inbounds.size());
        assertEquals("tun",inbounds.get(0).getAsJsonObject().get("type").getAsString());
        var mixed=inbounds.get(1).getAsJsonObject();assertEquals("127.0.0.1",mixed.get("listen").getAsString());assertEquals(19419,mixed.get("listen_port").getAsInt());
        assertEquals("proxy",config.getAsJsonObject("route").get("final").getAsString());
        assertEquals(1,config.getAsJsonArray("outbounds").size());
    }
    @Test public void offlineImportedProfilesSurviveRefreshWithoutBecomingProvenEntries(){
        List<Server> imported=ServerRepository.parseFeed("ss://aes-256-gcm:offline@example.org:443", "import:");
        assertEquals(1,imported.size());BootstrapHistory history=new BootstrapHistory("{}");
        assertEquals(imported,history.retain(List.of(),imported));assertFalse(history.contains(imported.get(0)));
        List<Server> remote=ServerRepository.parseFeed("ss://aes-256-gcm:offline@example.org:443", "feed:");
        List<Server> refreshed=history.retain(remote,imported);assertTrue(refreshed.get(0).id().startsWith("import:"));
        assertEquals(imported,history.retain(List.of(),refreshed));
        assertTrue(ServerRepository.parseFeed("ss://aes-256-gcm:offline@127.0.0.1:443", "import:").isEmpty());
    }
}
