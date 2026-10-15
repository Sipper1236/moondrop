package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
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
}
