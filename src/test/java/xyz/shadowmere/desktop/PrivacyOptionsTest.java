package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyOptionsTest {
    @TempDir Path data;
    @Test void cleanAndUpgradedSettingsNeverImplicitlyConsentToGeolocation()throws Exception {
        assertFalse(AppSettings.load(data).privacy().detectCountry());
        Files.writeString(data.resolve("settings.properties"),"nearbyFirst=true\nautomaticTesting=true\n");
        AppSettings upgraded=AppSettings.load(data);
        assertTrue(upgraded.nearbyFirst());assertFalse(upgraded.privacy().detectCountry());
        AtomicInteger requests=new AtomicInteger();
        ServerProximity location=new ServerProximity(data,()->{requests.incrementAndGet();return "{\"success\":true,\"country_code\":\"US\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);},Clock.systemUTC());
        assertEquals(Optional.empty(),location.locate(upgraded.privacy().detectCountry(),upgraded.privacy().manualCountry()));
        assertEquals(Optional.of("CA"),location.locate(false,"CA"));assertEquals(0,requests.get());
        assertEquals(Optional.of("US"),location.locate(true,""));assertEquals(1,requests.get());
        location.resetConsent();assertEquals(Optional.empty(),location.locate(false,""));assertEquals(1,requests.get());
    }
    @Test void privacyChoicesPersistWithoutSecretsAndSuppressBulkTests()throws Exception {
        PrivacyOptions privacy=new PrivacyOptions(true,true,true,"ca",true,true,"a".repeat(64),true,true);
        AppSettings settings=new AppSettings(true,true,true,true,Set.copyOf(AppSettings.PROTOCOLS),false,privacy);
        settings.save(data);assertEquals(settings,AppSettings.load(data));assertFalse(settings.bulkTestingAllowed());
        assertEquals("CA",settings.privacy().manualCountry());
        String saved=Files.readString(data.resolve("settings.properties"));assertFalse(saved.contains("ss://"));assertFalse(saved.contains("password"));
    }
    @Test void ordinaryWorkflowRemainsAvailableButWarningAndRouteOptionsNeverScan() {
        assertTrue(AppSettings.defaults().bulkTestingAllowed());assertFalse(AppSettings.defaults().privacy().privateConnect());
        for(PrivacyOptions privacy:List.of(new PrivacyOptions(true,false,false,"",false,false,"",false,false),
                new PrivacyOptions(false,true,false,"",false,false,"",false,false),
                new PrivacyOptions(false,false,false,"",false,true,"a".repeat(64),false,false),
                new PrivacyOptions(false,false,false,"",false,false,"",true,false))) {
            assertTrue(privacy.singleAttempt());assertFalse(new AppSettings(true,true,true,true,Set.copyOf(AppSettings.PROTOCOLS),false,privacy).bulkTestingAllowed());
        }
    }
    @Test void missingPreferencesPreserveEncryptedCatalogProtectionUntilExplicitOptOut()throws Exception {
        Files.writeString(data.resolve("server-catalog.json"),"{\"version\":2,\"servers\":[]}");
        assertTrue(AppSettings.load(data).privacy().secureCatalog());
        Files.writeString(data.resolve("settings.properties"),"automaticTesting=false\n");
        assertTrue(AppSettings.load(data).privacy().secureCatalog());
        Files.writeString(data.resolve("settings.properties"),"privacy.secureCatalog=false\n");
        assertFalse(AppSettings.load(data).privacy().secureCatalog());
    }
    @Test void invalidManualLocationAndUnattachedStrictModeAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new PrivacyOptions(false,false,false,"no-country",false,false,"",false,false));
        assertThrows(IllegalArgumentException.class,()->new PrivacyOptions(false,false,false,"",false,false,"",false,true));
    }
}
