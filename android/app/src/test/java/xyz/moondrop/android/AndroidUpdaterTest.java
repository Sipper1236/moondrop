package xyz.moondrop.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidUpdaterTest {
    @Test public void onlyNewerAndroidVersionsAreOffered() {
        assertTrue(AndroidUpdater.isNewer("0.2.0","0.1.9"));
        assertTrue(AndroidUpdater.isNewer("1.0.0","0.99.99"));
        assertFalse(AndroidUpdater.isNewer("0.1.1","0.1.1"));
        assertFalse(AndroidUpdater.isNewer("0.1.0","0.1.1"));
        assertFalse(AndroidUpdater.isNewer("latest","0.1.1"));
    }

    @Test public void checksumMustNameTheExactApk() throws Exception {
        String hash="a".repeat(64);
        assertEquals(hash,AndroidUpdater.checksum(hash+"  Moondrop-0.2.0-Android.apk\n","Moondrop-0.2.0-Android.apk"));
        assertThrows(java.io.IOException.class,()->AndroidUpdater.checksum(hash+"  another.apk\n","Moondrop-0.2.0-Android.apk"));
        assertThrows(java.io.IOException.class,()->AndroidUpdater.checksum("bad  Moondrop-0.2.0-Android.apk\n","Moondrop-0.2.0-Android.apk"));
    }
}
