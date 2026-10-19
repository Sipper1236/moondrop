package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ServerTest {
    private Server parse(String link) {return Server.parse("1",link,"CA","Toronto","",true);}
    private String b64(String s) {return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}

    @Test void acceptsSip002AndPreservesPasswordColonsAndUnicode() {
        Server s=parse("ss://"+b64("chacha20-ietf-poly1305:secret:with:colons-雪")+"@example.com:8388#Toronto");
        assertEquals("secret:with:colons-雪",s.password());assertEquals("example.com",s.host());assertEquals(8388,s.port());
        assertEquals("Canada",s.countryName());assertFalse(s.toString().contains("secret"));
    }
    @Test void acceptsLegacyWholeLinkAndIpv6() {
        Server s=parse("ss://"+b64("aes-256-gcm:key@[2606:4700:4700::1111]:443"));
        assertEquals("2606:4700:4700::1111",s.host());assertEquals("aes-256-gcm",s.method());
    }
    @Test void decodesPercentEscapedAuthWithoutChangingPlus() {
        assertEquals("a+b:c",parse("ss://aes-256-gcm:a+b%3Ac@host.test:443").password());
    }
    @Test void rejectsMalformedPortsAndPlugins() {
        assertThrows(IllegalArgumentException.class,()->parse("ss://"+b64("aes-256-gcm:key")+"@host.test:99999"));
        assertThrows(IllegalArgumentException.class,()->parse("ss://"+b64("aes-256-gcm:key")+"@host.test:443?plugin=v2ray-plugin"));
        assertThrows(IllegalArgumentException.class,()->parse("ss://"+b64("aes-256-gcm:key")+"@host.test:443?%70lugin=obfs-local"));
        assertThrows(IllegalArgumentException.class,()->parse("ss://"+b64("aes-256-gcm:key")+"@host.test:443?PLUGIN=obfs-local"));
        assertThrows(IllegalArgumentException.class,()->parse("https://host.test"));
    }
    @Test void rejectsUnencryptedAndUnsupportedMethodsFromAnySource() {
        for(String method:new String[]{"none","dummy","chacha20","unknown","rc4-md5","aes-128-cfb","aes-256-ctr","chacha20-ietf"})
            assertFalse(Server.supportsEncryptedMethod(method));
    }
    @Test void outboundCannotBeReplacedWithPlaintextOrDirectRouting() {
        Server secure=ProxyLinks.parse("test","trojan://secret@example.test:443?sni=example.test","","","",true);
        for(String field:new String[]{"type","enabled","insecure","detour"}) {
            var profile=secure.outbound();
            if(field.equals("type"))profile.addProperty("type","direct");
            else if(field.equals("detour"))profile.addProperty("detour","direct");
            else profile.getAsJsonObject("tls").addProperty(field,field.equals("insecure"));
            Server altered=new Server(secure.id(),secure.host(),secure.port(),secure.method(),secure.password(),secure.country(),secure.location(),"",true,secure.protocol(),profile.toString());
            assertThrows(IllegalArgumentException.class,altered::outbound);
        }
    }
    @Test void mapsDesktopArchitectureNames() {
        assertEquals("darwin-arm64",CoreInstaller.platform("Mac OS X","aarch64"));
        assertEquals("darwin-amd64",CoreInstaller.platform("Mac OS X","x86_64"));
        assertEquals("windows-amd64",CoreInstaller.platform("Windows 11","amd64"));
        assertEquals("windows-arm64",CoreInstaller.platform("Windows 11","arm64"));
    }
}
