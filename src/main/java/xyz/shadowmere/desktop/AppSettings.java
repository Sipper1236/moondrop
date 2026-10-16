package xyz.shadowmere.desktop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.Set;
import java.util.List;

/** User preferences, separate from connection credentials and speed results. */
public record AppSettings(boolean automaticUpdates, boolean automaticTesting,
                          boolean nearbyFirst, boolean systemProxy, Set<String> enabledProtocols) {
    public static final List<String> PROTOCOLS=List.of("shadowsocks","vless","trojan","hysteria2","tuic");
    public AppSettings {
        enabledProtocols=Set.copyOf(enabledProtocols);
        if(!PROTOCOLS.containsAll(enabledProtocols))throw new IllegalArgumentException("Unsupported protocol preference");
    }
    public AppSettings(boolean automaticUpdates,boolean automaticTesting,boolean nearbyFirst,boolean systemProxy) {
        this(automaticUpdates,automaticTesting,nearbyFirst,systemProxy,Set.copyOf(PROTOCOLS));
    }
    public boolean allows(Server server) { return enabledProtocols.contains(server.protocol()); }
    public List<Server> eligible(List<Server> servers) { return servers.stream().filter(this::allows).toList(); }
    public static AppSettings defaults() { return new AppSettings(true, true, true, true); }
    public static AppSettings load(Path data) throws IOException {
        Path file = data.resolve("settings.properties");
        if (!Files.exists(file)) return defaults();
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
        return new AppSettings(value(values,"automaticUpdates"), value(values,"automaticTesting"),
                value(values,"nearbyFirst"), value(values,"systemProxy"),
                PROTOCOLS.stream().filter(protocol -> value(values,"protocol."+protocol)).collect(java.util.stream.Collectors.toSet()));
    }
    private static boolean value(Properties values, String name) {
        return !"false".equalsIgnoreCase(values.getProperty(name, "true"));
    }
    public void save(Path data) throws IOException {
        Files.createDirectories(data);
        Properties values = new Properties();
        values.setProperty("automaticUpdates", Boolean.toString(automaticUpdates));
        values.setProperty("automaticTesting", Boolean.toString(automaticTesting));
        values.setProperty("nearbyFirst", Boolean.toString(nearbyFirst));
        values.setProperty("systemProxy", Boolean.toString(systemProxy));
        for(String protocol:PROTOCOLS)values.setProperty("protocol."+protocol,Boolean.toString(enabledProtocols.contains(protocol)));
        Path temporary = Files.createTempFile(data,"settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { values.store(writer,"Moondrop preferences"); }
            try { Files.move(temporary,data.resolve("settings.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary,data.resolve("settings.properties"),StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
