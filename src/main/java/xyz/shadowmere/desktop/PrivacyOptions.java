package xyz.shadowmere.desktop;

import java.util.*;

/** Optional privacy behavior; does not relax protocol or untrusted-feed validation. */
public record PrivacyOptions(boolean privateConnect, boolean warnBeforeContact,
                             boolean detectCountry, String manualCountry,
                             boolean secureCatalog, boolean twoHop, String entryKey,
                             boolean fullDevice, boolean strictTunnel) {
    public PrivacyOptions {
        manualCountry=manualCountry==null?"":manualCountry.strip().toUpperCase(Locale.ROOT);
        if(!manualCountry.isEmpty()&&!Set.of(Locale.getISOCountries()).contains(manualCountry))
            throw new IllegalArgumentException("Choose a valid country");
        entryKey=entryKey==null?"":entryKey;
        if(!entryKey.isEmpty()&&!entryKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid entry selection");
        if(strictTunnel&&!fullDevice)throw new IllegalArgumentException("Strict blocking requires full-device mode");
    }
    public static PrivacyOptions defaults(){return new PrivacyOptions(false,false,false,"",false,false,"",false,false);}
    public boolean singleAttempt(){return privateConnect||warnBeforeContact||twoHop||fullDevice;}
    public boolean contactDisclosure(){return privateConnect||warnBeforeContact||twoHop||fullDevice;}
    static PrivacyOptions load(Properties p){return new PrivacyOptions(flag(p,"privateConnect"),flag(p,"warnBeforeContact"),flag(p,"detectCountry"),p.getProperty("privacy.manualCountry",""),flag(p,"secureCatalog"),flag(p,"twoHop"),p.getProperty("privacy.entryKey",""),flag(p,"fullDevice"),flag(p,"strictTunnel"));}
    private static boolean flag(Properties p,String key){return Boolean.parseBoolean(p.getProperty("privacy."+key,"false"));}
    void save(Properties p){
        p.setProperty("privacy.privateConnect",Boolean.toString(privateConnect));p.setProperty("privacy.warnBeforeContact",Boolean.toString(warnBeforeContact));
        p.setProperty("privacy.detectCountry",Boolean.toString(detectCountry));p.setProperty("privacy.manualCountry",manualCountry);
        p.setProperty("privacy.secureCatalog",Boolean.toString(secureCatalog));p.setProperty("privacy.twoHop",Boolean.toString(twoHop));p.setProperty("privacy.entryKey",entryKey);
        p.setProperty("privacy.fullDevice",Boolean.toString(fullDevice));p.setProperty("privacy.strictTunnel",Boolean.toString(strictTunnel));
    }
}
