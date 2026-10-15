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
        Server s=parse("ss://"+b64("aes-256-gcm:key@[2001:db8::1]:443"));
        assertEquals("2001:db8::1",s.host());assertEquals("aes-256-gcm",s.method());
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
        for(String method:new String[]{"none","dummy","chacha20","unknown"})
            assertFalse(Server.supportsEncryptedMethod(method));
    }
    @Test void mapsDesktopArchitectureNames() {
        assertEquals("darwin-arm64",CoreInstaller.platform("Mac OS X","aarch64"));
        assertEquals("darwin-amd64",CoreInstaller.platform("Mac OS X","x86_64"));
        assertEquals("windows-amd64",CoreInstaller.platform("Windows 11","amd64"));
        assertEquals("windows-arm64",CoreInstaller.platform("Windows 11","arm64"));
    }
}
