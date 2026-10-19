package xyz.moondrop.android;

import org.junit.Test;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class PreferencesTest {
    @Test public void newOptionsKeepExistingInstallDefaults(){
        for(Preferences value:List.of(Preferences.defaults(),new Preferences(false,true,true,Preferences.PROTOCOLS),new Preferences(false,true,true,Preferences.PROTOCOLS,false,"CA"),new Preferences(false,true,true,Preferences.PROTOCOLS,false,"CA",""))){
            assertFalse(value.confirmProxyContact);assertTrue(value.automaticUpdates);assertFalse(value.singleServerMode());
        }
    }
    @Test public void contactConfirmationIsIndependentOfPrivacyAndStoredTestingChoice(){
        Preferences selected=Preferences.defaults().withConfirmProxyContact(true);
        assertTrue(selected.singleServerMode());assertFalse(selected.privacyMode);assertTrue(selected.automaticTesting);
        assertFalse(selected.withConfirmProxyContact(false).singleServerMode());
        assertTrue(selected.withPrivacyMode(true).withConfirmProxyContact(false).singleServerMode());
        assertTrue(selected.withEntryKey("entry").withConfirmProxyContact(false).singleServerMode());
    }
    @Test public void allSettingCopiesPreserveIndependentOptions(){
        Preferences selected=Preferences.defaults().withConfirmProxyContact(true).withAutomaticUpdates(false);
        for(Preferences copy:List.of(selected.withAdvancedMode(true),selected.withAutomaticTesting(false),selected.withNearbyFirst(false),selected.withEnabledProtocols(Set.of("trojan")),selected.withPrivacyMode(true),selected.withManualCountry("CA"),selected.withEntryKey("entry"))){
            assertTrue(copy.confirmProxyContact);assertFalse(copy.automaticUpdates);assertTrue(copy.singleServerMode());
        }
    }
    @Test public void updateChoiceDoesNotChangeConnectionPolicy(){
        Preferences selected=Preferences.defaults().withAutomaticUpdates(false);
        assertFalse(selected.automaticUpdates);assertTrue(selected.automaticTesting);assertFalse(selected.singleServerMode());
        assertTrue(selected.withAutomaticUpdates(true).automaticUpdates);
    }
    @Test public void optionsRemainAvailableWithoutAdvancedMode(){
        Preferences value=Preferences.defaults().withManualCountry("ca").withEntryKey("entry").withConfirmProxyContact(true).withAutomaticUpdates(false);
        assertFalse(value.advancedMode);assertEquals("CA",value.manualCountry);assertEquals("entry",value.entryKey);
        assertTrue(value.confirmProxyContact);assertFalse(value.automaticUpdates);
    }
}
