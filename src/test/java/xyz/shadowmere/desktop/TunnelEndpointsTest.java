package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class TunnelEndpointsTest {
    @TempDir Path data;
    private Server server(String id,String host,int port) {return new Server(id,host,port,"aes-256-gcm","fixture","","","",true);}
    private TunnelEndpoints blocked() {return new TunnelEndpoints(data,host->{throw new AssertionError("DNS must not run while blocked");});}
    @Test void publicLiteralsWorkWithoutDnsEvenWhenBlocked() throws Exception {
        assertEquals("1.1.1.1",blocked().outbound(server("feed:x","1.1.1.1",443),true).get("server").getAsString());
        assertTrue(blocked().outbound(server("feed:x","[2606:4700:4700::1111]",443),true).get("server").getAsString().contains(":"));
    }
    @Test void resolvedEndpointPersistsForBlockedReconnectAndKeepsTlsIdentity() throws Exception {
        Server s=ProxyLinks.parse("feed:x","trojan://fixture@proxy.example:443","","","",true);
        var resolver=new TunnelEndpoints(data,host->new InetAddress[]{InetAddress.getByName("1.1.1.1")});
        assertEquals("1.1.1.1",resolver.outbound(s,false).get("server").getAsString());
        var cached=blocked().outbound(s,true);
        assertEquals("1.1.1.1",cached.get("server").getAsString());
        assertEquals("proxy.example",cached.getAsJsonObject("tls").get("server_name").getAsString());
        assertThrows(IOException.class,()->blocked().outbound(server("feed:x","other.example",443),true));
        assertThrows(IOException.class,()->blocked().outbound(server("feed:x","proxy.example",444),true));
    }
    @Test void blockedUnknownHostnameDoesNotConsultDns() {
        assertThrows(IOException.class,()->blocked().outbound(server("feed:x","missing.example",443),true));
    }
    @Test void privateEndpointsNeverReachTunnelEvenForManualProfiles() {
        for(String host:new String[]{"127.0.0.1","192.168.1.1","169.254.169.254","::1","::ffff:127.0.0.1"})
            for(boolean blocked:new boolean[]{false,true})
                assertThrows(Exception.class,()->blocked().outbound(server("manual:home",host,443),blocked),host);
    }
    @Test void rejectsMixedPublicPrivateDnsAnswersBeforeSaving() {
        var resolver=new TunnelEndpoints(data,host->new InetAddress[]{InetAddress.getByName("1.1.1.1"),InetAddress.getByName("10.0.0.1")});
        assertThrows(IOException.class,()->resolver.outbound(server("manual:home","home.example",443),false));
        assertFalse(Files.exists(data.resolve("tunnel-endpoints.json")));
    }
    @Test void cachedHostnamesAreNeverResolved() throws Exception {
        var resolver=new TunnelEndpoints(data,host->new InetAddress[]{InetAddress.getByName("1.1.1.1")});
        Server s=server("feed:x","proxy.example",443);resolver.outbound(s,false);
        Path cache=data.resolve("tunnel-endpoints.json");
        Files.writeString(cache,Files.readString(cache).replace("1.1.1.1","attacker.example"));
        assertThrows(IOException.class,()->blocked().outbound(s,true));
    }
}
