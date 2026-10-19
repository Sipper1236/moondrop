package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ShadowsocksFeedTest {
    @Test void feedUsesConfiguredProxyEvenWhenOriginCannotResolve() throws Exception {
        var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicReference<String> requested=new java.util.concurrent.atomic.AtomicReference<>();
        proxy.createContext("/",exchange->{
            requested.set(exchange.getRequestURI().toString());
            byte[] response=link("aes-256-gcm","test-key","").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var body=exchange.getResponseBody()){body.write(response);}
        });
        proxy.start();
        try {
            var endpoint=java.net.URI.create("http://unresolvable.invalid/servers");
            var feed=new ShadowsocksFeed(endpoint,"test:",false,ServerCatalog.feedClient(proxy.getAddress().getPort()));
            assertEquals(1,feed.fetch().size());
            assertEquals(endpoint.toString(),requested.get());
        } finally {proxy.stop(0);}
    }
    @Test void failedProxyNeverFallsBackToDirectFeed() throws Exception {
        var origin=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger contacts=new java.util.concurrent.atomic.AtomicInteger();
        origin.createContext("/",exchange->{contacts.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        origin.start();
        int closedPort;
        try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){closedPort=socket.getLocalPort();}
        try {
            var endpoint=java.net.URI.create("http://127.0.0.1:"+origin.getAddress().getPort()+"/servers");
            var feed=new ShadowsocksFeed(endpoint,"test:",false,ServerCatalog.feedClient(closedPort));
            assertThrows(IOException.class,feed::fetch);
            assertEquals(0,contacts.get());
        } finally {origin.stop(0);}
    }
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
    @Test void protectedFeedsKeepVerifiedTlsProfilesAndRejectDowngrades() throws Exception {
        String uuid="bf000d23-0752-40b4-affe-68f7707a9661";
        String vless="vless://"+uuid+"@example.test:443?security=tls&sni=cert.test";
        var report=ShadowsocksFeed.parseProtectedReport(vless+"#%F0%9F%87%A8%F0%9F%87%A6%20Example\n"+
                "vless://"+uuid+"@example.test:443?sni=cert.test&security=tls#Duplicate\n"+
                "trojan://secret@example.test:443?sni=cert.test\n"+
                "hy2://secret@example.test:443?sni=cert.test\n"+
                "tuic://"+uuid+":secret@example.test:443?sni=cert.test\n"+
                "trojan://secret@example.test:443?insecure=1\n"+
                "vless://"+uuid+"@example.test:443?security=none\nhttp://example.test:8080\nvmess://not-supported", "morpheus:");
        assertEquals(4,report.servers().size());assertEquals(1,report.duplicates());
        assertEquals(Set.of("vless","trojan","hysteria2","tuic"),report.servers().stream().map(Server::protocol).collect(java.util.stream.Collectors.toSet()));
        assertEquals("CA",report.servers().get(0).country());
        for(Server server:report.servers()) {
            assertEquals("Morpheus",server.sourceName());
            assertTrue(server.outbound().getAsJsonObject("tls").get("enabled").getAsBoolean());
            assertFalse(server.outbound().getAsJsonObject("tls").get("insecure").getAsBoolean());
            assertEquals(server.protocol().equals("hysteria2")||server.protocol().equals("tuic"),server.usesUdpTransport());
        }
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
