package xyz.shadowmere.desktop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class AppUpdaterTest {
    @TempDir Path directory;
    @Test void comparesStablePrereleaseNumericIdentifiersAndBuildMetadata() {
        assertTrue(AppUpdater.compareVersions("0.2.1", "0.2.0") > 0);
        assertTrue(AppUpdater.compareVersions("v0.3.0-beta.2", "0.3.0") < 0);
        assertTrue(AppUpdater.compareVersions("0.3.0-beta.11", "0.3.0-beta.2") > 0);
        assertTrue(AppUpdater.compareVersions("0.3.0-beta.2", "0.3.0-beta") > 0);
        assertTrue(AppUpdater.compareVersions("0.3.0-1", "0.3.0-beta") < 0);
        assertEquals(0, AppUpdater.compareVersions("0.3.0+build.1", "0.3.0+build.2"));
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.compareVersions("0.3.0-beta.01", "0.3.0"));
    }
    @Test void checksumFileMatchesOnlyTheExactJarFilename() {
        String hash = "ab".repeat(32);
        assertEquals(hash, AppUpdater.checksum(hash + "  other.jar\n" + hash + " *moondrop.jar\n"));
        assertNull(AppUpdater.checksum(hash + "  ../moondrop.jar\n"));
        assertNull(AppUpdater.checksum(hash + "  moondrop.jar.exe\n"));
        assertNull(AppUpdater.checksum("bad  moondrop.jar\n"));
    }
    private JsonObject metadata() {
        JsonObject result = new JsonObject(); result.addProperty("tag_name", "v0.3.0-beta.2");
        result.addProperty("name", "Moondrop beta"); result.addProperty("prerelease", true); result.addProperty("draft", false);
        JsonObject asset = new JsonObject(); asset.addProperty("name", "moondrop.jar");
        asset.addProperty("browser_download_url", "https://github.com/Sipper1236/moondrop/releases/download/v0.3.0-beta.2/moondrop.jar");
        asset.addProperty("digest", "sha256:" + "a".repeat(64));
        JsonArray assets = new JsonArray(); assets.add(asset); result.add("assets", assets); return result;
    }
    @Test void parsesOfficialPrereleasesAndIgnoresDraftsAndMissingAssets() {
        var object = metadata(); var release = AppUpdater.parseRelease(object).orElseThrow();
        assertEquals("0.3.0-beta.2", release.version()); assertEquals("a".repeat(64), release.sha256());
        object.addProperty("draft", true); assertTrue(AppUpdater.parseRelease(object).isEmpty());
        object.addProperty("draft", false); object.add("assets", new JsonArray()); assertTrue(AppUpdater.parseRelease(object).isEmpty());
    }
    @Test void rejectsForeignAndSpoofedDownloadUrisAndMalformedDigests() {
        for (String url : List.of(
            "https://github.com/other/moondrop/releases/download/v0.3.0-beta.2/moondrop.jar",
            "https://github.com.evil.test/Sipper1236/moondrop/releases/download/v0.3.0-beta.2/moondrop.jar",
            "http://github.com/Sipper1236/moondrop/releases/download/v0.3.0-beta.2/moondrop.jar",
            "https://github.com/Sipper1236/moondrop/releases/download/v0.3.0-beta.2/moondrop.jar?redirect=evil",
            "https://github.com/Sipper1236/moondrop/releases/download/v9.0.0/moondrop.jar")) {
            var object = metadata(); object.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("browser_download_url", url);
            assertTrue(AppUpdater.parseRelease(object).isEmpty(), url);
        }
        var object = metadata(); object.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("digest", "sha256:bad");
        assertTrue(AppUpdater.parseRelease(object).isEmpty());
    }
    private byte[] jar(String main, String version, boolean includeMain) throws Exception {
        Manifest manifest = new Manifest(); var attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0"); attributes.putValue("Main-Class", main);
        attributes.putValue("Implementation-Version", version);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
            output.putNextEntry(new JarEntry(includeMain ? "xyz/shadowmere/desktop/Main.class" : "other.class"));
            output.write(new byte[] {1, 2, 3}); output.closeEntry();
        }
        return bytes.toByteArray();
    }
    private AppUpdater.Release release(byte[] bytes) throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new AppUpdater.Release("0.3.0", "Moondrop", URI.create("https://github.com/Sipper1236/moondrop/releases/tag/v0.3.0"),
            URI.create("https://github.com/Sipper1236/moondrop/releases/download/v0.3.0/moondrop.jar"), checksum);
    }
    @Test void stagesVerifiedJarAtomicallyAndLeavesNoTemporaryFiles() throws Exception {
        byte[] bytes = jar("xyz.shadowmere.desktop.Main", "0.3.0", true); List<String> progress = new ArrayList<>();
        Path file = new AppUpdater(directory).stage(release(bytes), new ByteArrayInputStream(bytes), progress::add);
        assertEquals("moondrop-0.3.0.jar", file.getFileName().toString()); assertArrayEquals(bytes, Files.readAllBytes(file));
        assertFalse(progress.isEmpty()); try (var files = Files.list(directory.resolve("updates"))) { assertEquals(1, files.count()); }
    }
    @Test void reusesStagedVerifiedUpdateWithoutANetworkRequest() throws Exception {
        byte[] bytes = jar("xyz.shadowmere.desktop.Main", "0.3.0", true);
        var updater = new AppUpdater(directory); var release = release(bytes);
        Path staged = updater.stage(release, new ByteArrayInputStream(bytes), ignored -> { });
        List<String> progress = new ArrayList<>();
        assertEquals(staged, updater.download(release, progress::add));
        assertEquals(List.of("Update already downloaded and verified"), progress);
        Files.write(staged, new byte[] {1, 2, 3});
        assertFalse(AppUpdater.validCached(staged, release));
    }
    @Test void checksumMismatchDiscardsTheDownload() throws Exception {
        byte[] bytes = jar("xyz.shadowmere.desktop.Main", "0.3.0", true);
        var updater = new AppUpdater(directory);
        IOException failure = assertThrows(IOException.class, () -> updater.stage(release(bytes), new ByteArrayInputStream(new byte[] {0}), ignored -> { }));
        assertTrue(failure.getMessage().contains("checksum")); assertUpdatesEmpty();
    }
    @Test void invalidJarOrMismatchedMetadataIsDiscardedDespiteValidChecksum() throws Exception {
        for (byte[] bytes : List.of(new byte[] {0, 1, 2}, jar("wrong.Main", "0.3.0", true),
            jar("xyz.shadowmere.desktop.Main", "0.2.0", true), jar("xyz.shadowmere.desktop.Main", "0.3.0", false))) {
            IOException failure = assertThrows(IOException.class, () -> new AppUpdater(directory).stage(release(bytes), new ByteArrayInputStream(bytes), ignored -> { }));
            assertTrue(failure.getMessage().contains("valid Moondrop")); assertUpdatesEmpty();
        }
    }
    @Test void publicDownloadRejectsUntrustedManuallyConstructedMetadataBeforeNetwork() throws Exception {
        var release = new AppUpdater.Release("0.3.0", "", URI.create("https://evil.test"), URI.create("https://evil.test/v0.3.0/moondrop.jar"), "a".repeat(64));
        assertThrows(IOException.class, () -> new AppUpdater(directory).download(release, ignored -> { }));
        assertFalse(Files.exists(directory.resolve("updates")));
    }
    private void assertUpdatesEmpty() throws IOException {
        try (var files = Files.list(directory.resolve("updates"))) { assertEquals(0, files.count()); }
    }
}
