package xyz.moondrop.android;

import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable settings; disabling a protocol never interrupts an existing session. */
public final class Preferences {
    public static final Set<String> PROTOCOLS = Set.of("shadowsocks", "vless", "trojan", "hysteria2", "tuic");
    public final boolean advancedMode, automaticTesting, nearbyFirst, privacyMode, confirmProxyContact, automaticUpdates;
    public final String manualCountry, entryKey;
    public final Set<String> enabledProtocols;
    public Preferences(boolean advancedMode, boolean automaticTesting, boolean nearbyFirst, Set<String> enabledProtocols) {
        this(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,false,"");
    }
    public Preferences(boolean advancedMode, boolean automaticTesting, boolean nearbyFirst, Set<String> enabledProtocols, boolean privacyMode, String manualCountry) {
        this(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,"");
    }
    public Preferences(boolean advancedMode, boolean automaticTesting, boolean nearbyFirst, Set<String> enabledProtocols, boolean privacyMode, String manualCountry, String entryKey) {
        this(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,entryKey,false,true);
    }
    public Preferences(boolean advancedMode, boolean automaticTesting, boolean nearbyFirst, Set<String> enabledProtocols, boolean privacyMode, String manualCountry, String entryKey, boolean confirmProxyContact, boolean automaticUpdates) {
        this.confirmProxyContact=confirmProxyContact;this.automaticUpdates=automaticUpdates;
        this.entryKey=entryKey==null?"":entryKey;this.privacyMode=privacyMode; this.manualCountry=normalizeCountry(manualCountry);
        this.advancedMode=advancedMode; this.automaticTesting=automaticTesting; this.nearbyFirst=nearbyFirst;
        LinkedHashSet<String> allowed=new LinkedHashSet<>(enabledProtocols); allowed.retainAll(PROTOCOLS);
        this.enabledProtocols=Set.copyOf(allowed);
    }
    public static Preferences defaults() { return new Preferences(false,true,true,PROTOCOLS); }
    public Preferences withAdvancedMode(boolean value) {return new Preferences(value,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withAutomaticTesting(boolean value) {return new Preferences(advancedMode,value,nearbyFirst,enabledProtocols,privacyMode,manualCountry,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withNearbyFirst(boolean value) {return new Preferences(advancedMode,automaticTesting,value,enabledProtocols,privacyMode,manualCountry,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withEnabledProtocols(Set<String> value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,value,privacyMode,manualCountry,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withPrivacyMode(boolean value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,value,manualCountry,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withManualCountry(String value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,value,entryKey,confirmProxyContact,automaticUpdates);}
    public Preferences withEntryKey(String value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,value,confirmProxyContact,automaticUpdates);}
    public Preferences withConfirmProxyContact(boolean value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,entryKey,value,automaticUpdates);}
    public Preferences withAutomaticUpdates(boolean value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,enabledProtocols,privacyMode,manualCountry,entryKey,confirmProxyContact,value);}
    public boolean singleServerMode(){return privacyMode||confirmProxyContact||!entryKey.isEmpty();}
    static String normalizeCountry(String value) {if(value==null)return "";String code=value.trim().toUpperCase(java.util.Locale.ROOT);return Set.of(java.util.Locale.getISOCountries()).contains(code)?code:"";}
}
