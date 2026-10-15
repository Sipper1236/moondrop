package xyz.shadowmere.desktop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProxyLinksTest {
    private static final String UUID="bf000d23-0752-40b4-affe-68f7707a9661";
    private static final String KEY="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0";
    private Server parse(String link){return ProxyLinks.parse("test",link,"US","","",true);}
    @Test void existingShadowsocksParsingAndConstructorRemainCompatible(){
        Server server=parse("ss://YWVzLTI1Ni1nY206c2VjcmV0@example.com:8443#label");
        assertEquals("shadowsocks",server.protocol());assertEquals("Shadowsocks",server.protocolName());
        assertEquals("secret",server.outbound().get("password").getAsString());assertFalse(server.outbound().has("tag"));
        Server legacy=new Server("x","example.com",8443,"aes-256-gcm","secret","US","","",true);
        assertEquals(legacy.outbound(),server.outbound());
    }
    @Test void vlessRealityProducesPinnedRealityAndVerifiedTls(){
        Server server=parse("vless://"+UUID+"@example.com:443?security=reality&pbk="+KEY+"&sid=0123456789abcdef&sni=www.example.org&flow=xtls-rprx-vision&fp=chrome#public%20label");
        JsonObject out=server.outbound(),tls=out.getAsJsonObject("tls");
        assertEquals("vless",out.get("type").getAsString());assertEquals(UUID,out.get("uuid").getAsString());
        assertEquals("xtls-rprx-vision",out.get("flow").getAsString());
        assertTrue(tls.get("enabled").getAsBoolean());assertFalse(tls.get("insecure").getAsBoolean());
        assertEquals(KEY,tls.getAsJsonObject("reality").get("public_key").getAsString());
        assertEquals("www.example.org",tls.get("server_name").getAsString());assertEquals("public label",server.location());
        assertFalse(server.toString().contains(UUID));assertFalse(server.toString().contains(KEY));
        out.addProperty("tag","changed");assertFalse(server.outbound().has("tag"));
    }
    @Test void tlsTransportsMapOnlySupportedConnectionFields(){
        var ws=parse("vless://"+UUID+"@example.com:443?security=tls&type=ws&host=cdn.example.org&path=%2Fproxy&alpn=h2%2Chttp%2F1.1").outbound();
        assertEquals("cdn.example.org",ws.getAsJsonObject("transport").getAsJsonObject("headers").get("Host").getAsString());
        assertEquals("/proxy",ws.getAsJsonObject("transport").get("path").getAsString());
        assertEquals(2,ws.getAsJsonObject("tls").getAsJsonArray("alpn").size());
        var grpc=parse("trojan://password@example.com:443?type=grpc&serviceName=proxy").outbound();
        assertEquals("proxy",grpc.getAsJsonObject("transport").get("service_name").getAsString());
        var upgrade=parse("trojan://password@example.com:443?type=httpupgrade&host=cdn.example.org&path=%2Fhello").outbound();
        assertEquals("cdn.example.org",upgrade.getAsJsonObject("transport").get("host").getAsString());
    }
    @Test void trojanPasswordDecodesOnceAndDefaultsToTls(){
        Server server=parse("trojan://pass%3Aword%2B%40@example.com:443?sni=certificate.example&allowInsecure=0#label");
        assertEquals("pass:word+@",server.password());assertEquals("Trojan",server.protocolName());
        assertEquals("certificate.example",server.outbound().getAsJsonObject("tls").get("server_name").getAsString());
        assertFalse(server.toString().contains(server.password()));
    }
    @Test void hysteria2SupportsAliasVerifiedSniAndNativeObfuscation(){
        Server server=parse("hy2://user%3Apass@example.com:8443/?sni=certificate.example&obfs=salamander&obfs-password=obfssecret");
        assertEquals("hysteria2",server.protocol());assertEquals("user:pass",server.password());
        assertEquals("salamander",server.outbound().getAsJsonObject("obfs").get("type").getAsString());
        assertTrue(server.outbound().getAsJsonObject("tls").get("enabled").getAsBoolean());
        assertFalse(server.toString().contains("obfssecret"));
    }
    @Test void tuicUsesUuidAndPasswordAndNativeQuicOptions(){
        Server server=parse("tuic://"+UUID+":password@example.com:443?sni=certificate.example&congestion_control=bbr&udp_relay_mode=native&alpn=h3");
        assertEquals("tuic",server.protocol());assertEquals("password",server.password());
        assertEquals(UUID,server.outbound().get("uuid").getAsString());
        assertEquals("bbr",server.outbound().get("congestion_control").getAsString());
        assertFalse(server.outbound().getAsJsonObject("tls").get("insecure").getAsBoolean());
    }
    @Test void profileSerializationIsDeterministicAcrossQueryOrder(){
        assertEquals(parse("vless://"+UUID+"@example.com:443?security=tls&type=ws&path=%2Fproxy&host=cdn.example.org").profileJson(),
                     parse("vless://"+UUID+"@example.com:443?host=cdn.example.org&path=%2Fproxy&type=ws&security=tls").profileJson());
    }
    @Test void plaintextInsecureMalformedAndUnsupportedOptionsNeverDowngrade(){
        for(String link:new String[]{
            "vless://"+UUID+"@example.com:443", "vless://"+UUID+"@example.com:443?security=none",
            "vless://"+UUID+"@example.com:443?security=reality", "vless://"+UUID+"@example.com:443?security=reality&pbk="+KEY+"&sid=abc",
            "vless://"+UUID+"@example.com:443?security=tls&type=xhttp", "vless://"+UUID+"@example.com:443?security=tls&encryption=aes",
            "vless://bad-uuid@example.com:443?security=tls", "trojan://secret@example.com:443?allowInsecure=1",
            "trojan://secret@example.com:443?insecure=true", "trojan://secret@example.com:443?security=none",
            "trojan://secret@example.com:443?security=tls&security=none", "trojan://secret@example.com:443?type=tcp&headerType=http",
            "trojan://secret@example.com:443?ech=unknown", "trojan://secret@example.com:443?type=grpc&mode=multi",
            "hy2://secret@example.com:443?insecure=1", "hy2://secret@example.com:443?obfs=salamander", "hy2://secret@example.com:443?type=ws",
            "tuic://"+UUID+"@example.com:443", "tuic://"+UUID+":password@example.com:443?disable_sni=true",
            "vmess://unsupported", "http://secret@example.com:443", "trojan://secret@example.com:70000", "trojan://secret@example.com:443?path=%0D%0A"
        }){
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->parse(link));
            assertFalse(failure.getMessage().contains("secret"));assertNull(failure.getCause());
        }
    }
}
