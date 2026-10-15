package xyz.shadowmere.desktop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** User preferences, separate from connection credentials and speed results. */
public record AppSettings(boolean automaticUpdates, boolean automaticTesting,
                          boolean nearbyFirst, boolean systemProxy) {
    public static AppSettings defaults() { return new AppSettings(true, true, true, true); }
    public static AppSettings load(Path data) throws IOException {
        Path file = data.resolve("settings.properties");
        if (!Files.exists(file)) return defaults();
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); }
        return new AppSettings(value(values,"automaticUpdates"), value(values,"automaticTesting"),
                value(values,"nearbyFirst"), value(values,"systemProxy"));
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
        Path temporary = Files.createTempFile(data,"settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { values.store(writer,"Moondrop preferences"); }
            try { Files.move(temporary,data.resolve("settings.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary,data.resolve("settings.properties"),StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
