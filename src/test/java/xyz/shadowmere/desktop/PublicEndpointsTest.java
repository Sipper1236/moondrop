package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class PublicEndpointsTest {
    private Server server(String host) { return new Server("feed:test",host,443,"aes-256-gcm","fixture","","","",true); }
    @Test void rejectsAllSpecialUseFamiliesWithoutProbes() {
        for(String host:new String[]{"0.0.0.0","10.0.0.1","127.0.0.1","127.1","192.168.1.1","169.254.169.254",
                "172.16.0.1","100.64.0.1","192.0.0.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1",
                "224.0.0.1","255.255.255.255","::","::1","::ffff:127.0.0.1","::ffff:192.168.1.1",
                "fc00::1","fe80::1","ff02::1","2001:db8::1","2001::1","2002:7f00:1::1","3fff::1",
                "localhost","localhost.","host.local","router","fe80::1%eth0"})
            assertThrows(IllegalArgumentException.class,()->PublicEndpoints.requirePublicLiteral(server(host)),host);
    }
    @Test void ingestionDoesNotResolveHostnames() {
        assertDoesNotThrow(()->PublicEndpoints.requirePublicLiteral(server("unresolvable.invalid")));
    }
    @Test void acceptsGlobalAddressesAndPinsOriginalTlsIdentity() {
        assertDoesNotThrow(()->PublicEndpoints.requirePublicLiteral(server("1.1.1.1")));
        assertDoesNotThrow(()->PublicEndpoints.requirePublicLiteral(server("2606:4700:4700::1111")));
        Server s=ProxyLinks.parse("feed:test","trojan://fixture@1.1.1.1:443?sni=certificate.example","","","",true);
        var profile=PublicEndpoints.outbound(s);
        assertEquals("1.1.1.1",profile.get("server").getAsString());
        assertEquals("certificate.example",profile.getAsJsonObject("tls").get("server_name").getAsString());
    }
    @Test void rejectsEntireMixedDnsAnswerSetBeforeAnyConnection() throws Exception {
        Server s=server("hostname.example");
        assertThrows(IOException.class,()->PublicEndpoints.checkedAddress(s,new InetAddress[]{InetAddress.getByName("1.1.1.1"),InetAddress.getByName("127.0.0.1")}));
        assertThrows(IOException.class,()->PublicEndpoints.checkedAddress(s,new InetAddress[]{InetAddress.getByName("::ffff:192.168.1.1")}));
    }
    @Test void manualProfilesRequireExplicitIdAndMayUseLan() throws Exception {
        Server s=new Server("manual:home","192.168.1.1",443,"aes-256-gcm","fixture","","","",true);
        assertDoesNotThrow(()->PublicEndpoints.requirePublicLiteral(s));
        assertEquals("192.168.1.1",PublicEndpoints.resolve(s).getHostAddress());
        assertThrows(IllegalArgumentException.class,()->PublicEndpoints.outbound(server("192.168.1.1")));
    }
    @Test void feedParsingRejectsPrivateEndpoints() throws Exception {
        var report=ShadowsocksFeed.parseReport("ss://aes-256-gcm:fixture@127.0.0.1:443\nss://aes-256-gcm:fixture@192.168.1.1:443\nss://aes-256-gcm:fixture@169.254.169.254:443\nss://aes-256-gcm:fixture@[::1]:443\nss://aes-256-gcm:fixture@1.1.1.1:443");
        assertEquals(1,report.servers().size());
        assertEquals(4,report.malformed());
    }
    @Test void chainConfigurationHasNoDirectFallbackAndRejectsSameEndpoint() {
        Server entry=server("1.1.1.1"),exit=server("8.8.8.8");
        var config=ProxyEngine.configurationChain(entry,exit);
        var out=config.getAsJsonArray("outbounds");
        assertEquals(2,out.size());
        assertEquals("chain-entry",out.get(0).getAsJsonObject().get("detour").getAsString());
        assertEquals("1.1.1.1",out.get(1).getAsJsonObject().get("server").getAsString());
        assertFalse(config.toString().contains("\"direct\""));
        assertThrows(IllegalArgumentException.class,()->ProxyEngine.configurationChain(entry,entry));
    }
    @Test void localProxyDetourIsLoopbackOnlyAndPinsCandidate() {
        var config=ProxyEngine.configurationViaLocalProxy(18001,server("1.1.1.1"),18000);
        var out=config.getAsJsonArray("outbounds");
        assertEquals("established-local-proxy",out.get(0).getAsJsonObject().get("detour").getAsString());
        assertEquals("1.1.1.1",out.get(0).getAsJsonObject().get("server").getAsString());
        assertEquals("127.0.0.1",out.get(1).getAsJsonObject().get("server").getAsString());
        assertEquals("socks",out.get(1).getAsJsonObject().get("type").getAsString());
        assertThrows(IllegalArgumentException.class,()->ProxyEngine.configurationViaLocalProxy(18000,server("1.1.1.1"),18000));
        assertThrows(IllegalArgumentException.class,()->ProxyEngine.configurationViaLocalProxy(18001,server("127.0.0.1"),18000));
    }
    @Test void callerSuppliedDnsAnswersUseSameValidationAndTlsIdentity()throws Exception {
        Server tls=ProxyLinks.parse("feed:test","trojan://fixture@hostname.example:443","","","",true);
        var config=PublicEndpoints.outbound(tls,new InetAddress[]{InetAddress.getByName("1.1.1.1")});
        assertEquals("1.1.1.1",config.get("server").getAsString());
        assertEquals("hostname.example",config.getAsJsonObject("tls").get("server_name").getAsString());
        assertThrows(IllegalArgumentException.class,()->PublicEndpoints.outbound(tls,new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
    }
    @Test void wellKnownNat64OnlyPermitsPublicEmbeddedIpv4()throws Exception {
        for(String host:new String[]{"64:ff9b::808:808","64:ff9b::101:101"}) {
            assertTrue(PublicEndpoints.isPublic(InetAddress.getByName(host)),host);
            assertDoesNotThrow(()->PublicEndpoints.requirePublicLiteral(server(host)));
        }
        for(String host:new String[]{"64:ff9b::7f00:1","64:ff9b::c0a8:101","64:ff9b::a9fe:a9fe","64:ff9b::a00:1",
                "64:ff9b::6440:1","64:ff9b::e000:1","64:ff9b::","64:ff9b:1::808:808"}) {
            assertFalse(PublicEndpoints.isPublic(InetAddress.getByName(host)),host);
            assertThrows(IllegalArgumentException.class,()->PublicEndpoints.requirePublicLiteral(server(host)));
        }
    }
    @Test void cachedLegacyCipherCannotProduceAnOutbound() {
        for(String cipher:new String[]{"rc4-md5","aes-256-cfb","aes-256-ctr","chacha20-ietf","none"}) {
            Server s=new Server("cached","1.1.1.1",443,cipher,"fixture","","","",true);
            assertThrows(IllegalArgumentException.class,s::outbound);
        }
    }
}
