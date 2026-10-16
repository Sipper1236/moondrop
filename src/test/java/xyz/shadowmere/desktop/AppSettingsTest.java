package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    @TempDir Path data;
    @Test void missingPreferencesPreserveExistingBehavior() throws Exception {
        assertEquals(new AppSettings(true,true,true,true),AppSettings.load(data));
    }
    @Test void preferencesSurviveRestartAndReplacement() throws Exception {
        AppSettings disabled=new AppSettings(false,false,false,false);
        disabled.save(data);
        assertEquals(disabled,AppSettings.load(data));
        AppSettings mixed=new AppSettings(true,false,true,false);
        mixed.save(data);
        assertEquals(mixed,AppSettings.load(data));
        try(var files=Files.list(data)) { assertEquals(1,files.count()); }
    }
    @Test void incompleteOlderSettingsUseDefaultsForNewOptions() throws Exception {
        Files.writeString(data.resolve("settings.properties"),"automaticTesting=false\n");
        assertEquals(new AppSettings(true,false,true,true),AppSettings.load(data));
    }
    @Test void advancedModeDefaultsToOffForExistingInstallations() throws Exception {
        assertFalse(AppSettings.defaults().advancedMode());
        assertFalse(new AppSettings(true,true,true,true,Set.of("vless")).advancedMode());
        Files.writeString(data.resolve("settings.properties"),"automaticTesting=false\nprotocol.trojan=false\n");
        AppSettings loaded=AppSettings.load(data);
        assertFalse(loaded.advancedMode());
        assertFalse(loaded.automaticTesting());
        assertFalse(loaded.enabledProtocols().contains("trojan"));
    }
    @Test void advancedModeSurvivesRestartAndCanBeTurnedOff() throws Exception {
        AppSettings advanced=new AppSettings(true,false,true,false,Set.of("vless","trojan"),true);
        advanced.save(data);
        assertEquals(advanced,AppSettings.load(data));
        AppSettings simple=new AppSettings(true,false,true,false,Set.of("vless","trojan"),false);
        simple.save(data);
        assertEquals(simple,AppSettings.load(data));
    }
    @Test void protocolSelectionsSurviveRestartIncludingAllDisabled() throws Exception {
        AppSettings selected=new AppSettings(true,true,true,true,Set.of("shadowsocks","vless"));
        selected.save(data);assertEquals(selected,AppSettings.load(data));
        AppSettings none=new AppSettings(true,true,true,true,Set.of());
        none.save(data);assertEquals(none,AppSettings.load(data));
    }
    @Test void disabledProtocolsAreExcludedFromCandidateLists() {
        Server ss=new Server("ss","localhost",443,"aes-256-gcm","test","","","",true);
        Server vless=ProxyLinks.parse("vless","vless://bf000d23-0752-40b4-affe-68f7707a9661@localhost:443?security=tls","","","",true);
        AppSettings onlyVless=new AppSettings(true,true,true,true,Set.of("vless"));
        assertFalse(onlyVless.allows(ss));assertTrue(onlyVless.allows(vless));
        assertEquals(List.of(vless),onlyVless.eligible(List.of(ss,vless)));
        assertEquals(List.of(),new AppSettings(true,true,true,true,Set.of()).eligible(List.of(ss,vless)));
    }
    @Test void protocolFilteringKeepsCachedRankingsForReenable() {
        Server ss=new Server("ss","localhost",443,"aes-256-gcm","test","","","",true);
        Server vless=ProxyLinks.parse("vless","vless://bf000d23-0752-40b4-affe-68f7707a9661@localhost:443?security=tls","","","",true);
        List<Server> all=List.of(ss,vless);
        PerformanceResults results=new PerformanceResults();
        ServerPerformance saved=ServerPerformance.measured(List.of(10L,11L,12L),3);
        results.record(ss,saved);
        List<Server> eligible=new AppSettings(true,true,true,true,Set.of("vless")).eligible(all);
        assertTrue(results.ranked(eligible).isEmpty());
        assertEquals(List.of(vless),results.quickCandidates(eligible));
        assertEquals(List.of(vless),results.pending(eligible,true));
        assertTrue(results.bandwidthCandidates(eligible,5).isEmpty());
        assertEquals(saved,results.get(ss));
        assertEquals(List.of(ss),results.ranked(AppSettings.defaults().eligible(all)));
        assertEquals(List.of(ss),results.bandwidthCandidates(AppSettings.defaults().eligible(all),5));
    }
}
