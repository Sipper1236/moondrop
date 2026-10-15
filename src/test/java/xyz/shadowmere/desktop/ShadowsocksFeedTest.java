package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ShadowsocksFeedTest {
    private String link(String method, String password, String label) {
        String auth = Base64.getEncoder().encodeToString((method + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "ss://" + auth + "@example.test:8388" + (label.isEmpty() ? "" : "#" + label);
    }
    @Test void importsOnlyCompatibleEntriesAndCountsIndependentSkips() throws Exception {
        String valid = link("aes-256-gcm", "private-password", "Canada");
        var report = ShadowsocksFeed.parseReport(valid + "\nnot a proxy\nss://invalid\n" +
            link("unsupported-cipher", "private-password", "") + "\n" + valid + "\n" +
            link("aes-128-gcm", "other-password", "") + "?plugin=obfs-local\n");
        assertEquals(1, report.servers().size()); assertEquals(2, report.malformed());
        assertEquals(1, report.unsupported()); assertEquals(1, report.plugins()); assertEquals(1, report.duplicates());
        assertTrue(report.servers().get(0).id().startsWith("gfp:"));
        assertFalse(report.servers().get(0).toString().contains("private-password"));
    }
    @Test void collectorKeepsItsSourceAndRejectsOtherProtocolsEvenInSsFeed() throws Exception {
        var report=ShadowsocksFeed.parseReport(link("aes-256-gcm","key","%F0%9F%87%BA%F0%9F%87%B8%20Example")+
                "\ntrojan://password@example.test:443\nvless://uuid@example.test:443\nhttp://example.test:8080\nsocks5://example.test:1080", "collector:");
        assertEquals(1,report.servers().size());assertEquals(4,report.malformed());
        assertTrue(report.servers().get(0).id().startsWith("collector:"));
        assertEquals("V2rayCollector",report.servers().get(0).sourceName());
    }
    @Test void morpheusUsesOnlyItsEncryptedShadowsocksEntries() throws Exception {
        var report=ShadowsocksFeed.parseReport(link("aes-128-gcm","key","Example")+"\n"+link("none","key","")+"\nsocks5://example.test:1080", "morpheus:");
        assertEquals(1,report.servers().size());assertEquals("Morpheus",report.servers().get(0).sourceName());
        assertEquals(1,report.unsupported());assertEquals(1,report.malformed());
    }
    @Test void dedupUsesCredentialsAndIgnoresLabelChanges() throws Exception {
        var servers = ShadowsocksFeed.parse(link("aes-256-gcm", "password-one", "first") + "\n" +
            link("aes-256-gcm", "password-one", "second") + "\n" + link("aes-256-gcm", "password-two", "third"));
        assertEquals(2, servers.size()); assertEquals("first", servers.get(0).displayName());
        assertNotEquals(servers.get(0).id(), servers.get(1).id());
    }
    @Test void excludesUnencryptedShadowsocksEvenWhenEngineSupportsIt() throws Exception {
        var report = ShadowsocksFeed.parseReport(link("none", "password", "") + "\n" + link("dummy", "password", ""));
        assertTrue(report.servers().isEmpty()); assertEquals(2, report.unsupported());
    }
    @Test void onlyExplicitValidCountryFlagsDetermineCountry() throws Exception {
        var servers = ShadowsocksFeed.parse(link("aes-256-gcm", "password-one", "%F0%9F%87%A8%F0%9F%87%A6%20Toronto") + "\n" +
            link("aes-256-gcm", "password-two", "US%20New%20York") + "\n" +
            link("aes-256-gcm", "password-three", "🇿🇿%20Unknown"));
        assertEquals("CA", servers.get(0).country()); assertEquals("", servers.get(1).country()); assertEquals("", servers.get(2).country());
        assertTrue(servers.get(0).displayName().contains("Toronto"));
    }
    @Test void labelsStayPlainBoundedAndCannotExposePasswordOrRawLinks() throws Exception {
        String label = "%3Chtml%3EHello%00%E2%80%AE%20https%3A%2F%2Fevil.test%2Fsecret%20" + "x".repeat(200);
        var servers = ShadowsocksFeed.parse(link("aes-256-gcm", "private-password", label) + "\n" +
            link("aes-256-gcm", "other-password", "other-password"));
        String display = servers.get(0).displayName();
        assertFalse(display.contains("<")); assertFalse(display.contains(":")); assertFalse(display.contains("\u202e"));
        assertFalse(display.contains("\0")); assertTrue(display.codePointCount(0, display.length()) <= 96);
        assertEquals("example.test", servers.get(1).displayName());
    }
    @Test void validates2022KeysAndRejectsEncodedPlugins() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        var report = ShadowsocksFeed.parseReport(link("2022-blake3-aes-128-gcm", key, "") + "\n" +
            link("2022-blake3-aes-256-gcm", key, "") + "\n" + link("aes-128-gcm", "password", "") + "?%70lugin=obfs-local");
        assertEquals(1, report.servers().size()); assertEquals(1, report.malformed()); assertEquals(1, report.plugins());
    }
    @Test void fetchClosesResponseAndEnforcesSizeLimit() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var api = new ShadowsocksFeed(() -> new ByteArrayInputStream(link("aes-256-gcm", "password", "").getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed.set(true); }
        }, Duration.ofSeconds(2));
        assertEquals(1, api.fetch().size()); assertTrue(closed.get());
        var huge = new ShadowsocksFeed(() -> new InputStream() {
            public int read() { return 'x'; }
            @Override public int read(byte[] bytes, int offset, int length) { Arrays.fill(bytes, offset, offset + length, (byte) 'x'); return length; }
        }, Duration.ofSeconds(3));
        assertTrue(assertThrows(IOException.class, huge::fetch).getMessage().contains("8 MiB"));
    }
    @Test void timeoutCoversStalledResponseBodyAndClosesIt() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var api = new ShadowsocksFeed(() -> new InputStream() {
            @Override public synchronized int read() {
                while (!closed.get()) {
                    try { wait(); } catch (InterruptedException ignored) { }
                }
                return -1;
            }
            @Override public synchronized void close() { closed.set(true); notifyAll(); }
        }, Duration.ofMillis(100));
        long start = System.nanoTime();
        assertTrue(assertThrows(IOException.class, api::fetch).getMessage().contains("too long"));
        assertTrue(closed.get()); assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
    }
    @Test void transportFailuresDoNotExposeUntrustedCredentials() {
        var api = new ShadowsocksFeed(() -> { throw new IOException("ss://raw-secret@example.test"); }, Duration.ofSeconds(1));
        IOException failure = assertThrows(IOException.class, api::fetch);
        assertFalse(failure.toString().contains("raw-secret")); assertNull(failure.getCause());
    }
}
