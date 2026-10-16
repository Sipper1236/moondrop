package xyz.moondrop.android;

import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable settings; disabling a protocol never interrupts an existing session. */
public final class Preferences {
    public static final Set<String> PROTOCOLS = Set.of("shadowsocks", "vless", "trojan", "hysteria2", "tuic");
    public final boolean advancedMode, automaticTesting, nearbyFirst;
    public final Set<String> enabledProtocols;
    public Preferences(boolean advancedMode, boolean automaticTesting, boolean nearbyFirst, Set<String> enabledProtocols) {
        this.advancedMode=advancedMode; this.automaticTesting=automaticTesting; this.nearbyFirst=nearbyFirst;
        LinkedHashSet<String> allowed=new LinkedHashSet<>(enabledProtocols); allowed.retainAll(PROTOCOLS);
        this.enabledProtocols=Set.copyOf(allowed);
    }
    public static Preferences defaults() { return new Preferences(false,true,true,PROTOCOLS); }
    public Preferences withAdvancedMode(boolean value) {return new Preferences(value,automaticTesting,nearbyFirst,enabledProtocols);}
    public Preferences withAutomaticTesting(boolean value) {return new Preferences(advancedMode,value,nearbyFirst,enabledProtocols);}
    public Preferences withNearbyFirst(boolean value) {return new Preferences(advancedMode,automaticTesting,value,enabledProtocols);}
    public Preferences withEnabledProtocols(Set<String> value) {return new Preferences(advancedMode,automaticTesting,nearbyFirst,value);}
}
