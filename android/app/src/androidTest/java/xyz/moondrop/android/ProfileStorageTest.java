package xyz.moondrop.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProfileStorageTest {
    @Test public void legacyProfilesMigrateToAuthenticatedKeystoreEncryption() throws Exception {
        File cache=File.createTempFile("moondrop-profiles-",".json",InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            Server server=new Server("fixture","8.8.8.8",443,"aes-256-gcm","private-storage-marker","CA","","",true);
            ServerRepository legacy=new ServerRepository(cache);legacy.save(List.of(server),Map.of());
            assertTrue(new String(Files.readAllBytes(cache.toPath()),StandardCharsets.UTF_8).contains("private-storage-marker"));
            ServerRepository encrypted=new ServerRepository(cache,new ProfileCipher());
            assertEquals("private-storage-marker",encrypted.load().servers().get(0).password());assertTrue(ProfileCipher.encrypted(cache));
            byte[] bytes=Files.readAllBytes(cache.toPath());assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("private-storage-marker"));
            assertEquals("private-storage-marker",new ServerRepository(cache,new ProfileCipher()).load().servers().get(0).password());
            bytes[bytes.length-1]^=1;Files.write(cache.toPath(),bytes);
            ServerRepository corrupt=new ServerRepository(cache,new ProfileCipher());assertTrue(corrupt.load().servers().isEmpty());assertTrue(corrupt.cacheUnavailable());
            try {corrupt.save(List.of(),Map.of());fail("Must preserve a cache that cannot be unlocked");}catch(java.io.IOException expected){}
            assertArrayEquals(bytes,Files.readAllBytes(cache.toPath()));corrupt.erase();assertFalse(cache.exists());
        }finally {cache.delete();}
    }
}
