package xyz.moondrop.android;

import org.junit.Test;
import java.util.*;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;

public class TestingPolicyTest {
    @Test public void automaticPassIsBoundedAndResumesAfterFreshCache() {
        List<Server> catalog=new ArrayList<>();
        for(int i=0;i<300;i++)catalog.add(new Server(""+i,"node"+i+".example",443,"aes-256-gcm","private","","","",true));
        List<Server> first=AppController.pending(catalog,Map.of());assertEquals(128,first.size());
        Map<String,Measurement> cache=new HashMap<>();
        for(Server server:first)cache.put(ServerRepository.key(server),new Measurement(-1,System.currentTimeMillis(),0,0,3));
        List<Server> second=AppController.pending(catalog,cache);assertEquals(128,second.size());assertEquals(catalog.get(128),second.get(0));
    }
    @Test public void expiredResultsAreRetested() {
        Server server=new Server("one","8.8.8.8",443,"aes-256-gcm","private","","","",true);
        Measurement stale=new Measurement(30,System.currentTimeMillis()-Measurement.MAX_AGE-1,0,3,3);
        assertEquals(List.of(server),AppController.pending(List.of(server),Map.of(ServerRepository.key(server),stale)));
    }
    @Test public void vpnDnsUsesEncryptedProxyWithSeparateEndpointBootstrap() {
        Server server=new Server("one","8.8.8.8",443,"aes-256-gcm","private","","","",true);
        com.google.gson.JsonObject config=com.google.gson.JsonParser.parseString(AppController.configuration(server,0,true)).getAsJsonObject();
        assertEquals("encrypted-dns",config.getAsJsonObject("dns").get("final").getAsString());
        assertEquals("encrypted-dns",config.getAsJsonObject("route").get("default_domain_resolver").getAsString());
        // Endpoint hosts are resolved and pinned before configuration; proxied feed domains use encrypted DNS.
        com.google.gson.JsonObject encrypted=config.getAsJsonObject("dns").getAsJsonArray("servers").get(1).getAsJsonObject();
        assertEquals("https",encrypted.get("type").getAsString());assertEquals("proxy",encrypted.get("detour").getAsString());
        assertEquals("hijack-dns",config.getAsJsonObject("route").getAsJsonArray("rules").get(0).getAsJsonObject().get("action").getAsString());
    }
    @Test public void privacyIsOptionalAndCountryNeverRequiresIpLookup() {
        Preferences defaults=Preferences.defaults();assertFalse(defaults.privacyMode);assertTrue(defaults.automaticTesting);
        Preferences privacy=defaults.withPrivacyMode(true).withManualCountry("ca").withAdvancedMode(true);
        assertTrue(privacy.privacyMode);assertEquals("CA",privacy.manualCountry);
        assertEquals("",privacy.withManualCountry("invalid").manualCountry);
    }
    @Test public void privacySelectionNeverExpandsToOtherServers() {
        Server first=new Server("first","8.8.8.8",443,"aes-256-gcm","private","","","",true);
        Server second=new Server("second","1.1.1.1",443,"aes-256-gcm","private","","","",true);
        assertEquals(List.of(first),AppController.privacyCandidates(List.of(first,second)));
        assertTrue(AppController.privacyCandidates(List.of()).isEmpty());
    }
    @Test public void twoHopConfigurationEnforcesRealDetourAndNoAutomaticScans() {
        Server entry=new Server("entry","8.8.8.8",443,"aes-256-gcm","entry-secret","","","",true);
        Server exit=new Server("exit","1.1.1.1",443,"aes-256-gcm","exit-secret","","","",true);
        com.google.gson.JsonObject root=com.google.gson.JsonParser.parseString(AppController.configuration(exit,1080,false,entry)).getAsJsonObject();
        com.google.gson.JsonArray outbounds=root.getAsJsonArray("outbounds");assertEquals(2,outbounds.size());
        assertEquals("entry",outbounds.get(0).getAsJsonObject().get("tag").getAsString());
        assertEquals("entry",outbounds.get(1).getAsJsonObject().get("detour").getAsString());
        assertEquals("proxy",root.getAsJsonObject("route").get("final").getAsString());
        assertTrue(Preferences.defaults().withEntryKey("entry").singleServerMode());
    }
}
