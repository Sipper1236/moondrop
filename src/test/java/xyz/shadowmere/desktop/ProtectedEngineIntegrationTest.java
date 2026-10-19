package xyz.shadowmere.desktop;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Entirely local encrypted round trips, including verification of a private test certificate. */
class ProtectedEngineIntegrationTest {
    @TempDir Path directory;
    private static final String UUID = "bf000d23-0752-40b4-affe-68f7707a9661";
    private static final String PASSWORD = "local-test-password";
    private static final char[] STORE_PASSWORD = "local-fixture-only".toCharArray();
    private Path core, certificate, key;
    private final Map<Process, Thread> readers = new IdentityHashMap<>();

    @BeforeEach void createLocalCertificate() throws Exception {
        String executable = System.getProperty("engine.test.path", "");
        Assumptions.assumeTrue(!executable.isEmpty() && Files.isExecutable(Path.of(executable)),
                "Set -Dengine.test.path for native encrypted integration tests");
        core = Path.of(executable);
        String tool = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", tool);
        Assumptions.assumeTrue(Files.isExecutable(keytool), "The JDK keytool is required for local TLS fixtures");
        Path store = directory.resolve("fixture.p12");
        Process generation = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "proxy",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", new String(STORE_PASSWORD),
                "-keypass", new String(STORE_PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(generation.waitFor(10, TimeUnit.SECONDS), "Local certificate generation timed out");
            assertEquals(0, generation.exitValue(), "Local certificate generation failed");
        } finally { stop(generation); }
        KeyStore fixture = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { fixture.load(input, STORE_PASSWORD); }
        certificate = directory.resolve("certificate.pem"); key = directory.resolve("private-key.pem");
        Files.writeString(certificate, pem("CERTIFICATE", fixture.getCertificate("proxy").getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", fixture.getKey("proxy", STORE_PASSWORD).getEncoded()));
    }

    private String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
    private int freeTcpPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return reservation.getLocalPort(); }
    }
    private int freeRemotePort(String protocol) throws IOException {
        if (protocol.equals("hysteria2") || protocol.equals("tuic")) {
            try (DatagramSocket reservation = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { return reservation.getLocalPort(); }
        }
        return freeTcpPort();
    }
    private Server server(String protocol, int remotePort, boolean trusted) {
        String auth = protocol.equals("vless") ? UUID : protocol.equals("tuic") ? UUID + ":" + PASSWORD : PASSWORD;
        String uri = protocol + "://" + auth + "@127.0.0.1:" + remotePort + "?sni=localhost"
                + (protocol.equals("vless") ? "&security=tls" : "");
        Server parsed = ProxyLinks.parse("manual:local-test", uri, "", "Local fixture", "", true);
        if (!trusted) return parsed;
        JsonObject profile = parsed.outbound();
        profile.getAsJsonObject("tls").addProperty("certificate_path", certificate.toString());
        return new Server(parsed.id(), parsed.host(), parsed.port(), parsed.method(), parsed.password(),
                parsed.country(), parsed.location(), parsed.lastChecked(), parsed.active(), parsed.protocol(), profile.toString());
    }
    private Process startRemote(String protocol, int port) throws Exception {
        JsonObject inbound = new JsonObject();
        inbound.addProperty("type", protocol); inbound.addProperty("listen", "127.0.0.1"); inbound.addProperty("listen_port", port);
        JsonObject user = new JsonObject(); user.addProperty("name", "local-test");
        if (protocol.equals("vless") || protocol.equals("tuic")) user.addProperty("uuid", UUID);
        if (!protocol.equals("vless")) user.addProperty("password", PASSWORD);
        JsonArray users = new JsonArray(); users.add(user); inbound.add("users", users);
        JsonObject tls = new JsonObject(); tls.addProperty("enabled", true);
        tls.addProperty("certificate_path", certificate.toString()); tls.addProperty("key_path", key.toString());
        inbound.add("tls", tls);
        JsonObject configuration = new JsonObject();
        JsonObject log = new JsonObject(); log.addProperty("level", "info"); configuration.add("log", log);
        JsonArray inbounds = new JsonArray(); inbounds.add(inbound); configuration.add("inbounds", inbounds);
        JsonObject direct = new JsonObject(); direct.addProperty("type", "direct");
        JsonArray outbounds = new JsonArray(); outbounds.add(direct); configuration.add("outbounds", outbounds);
        Path config = directory.resolve(protocol + "-remote.json");
        Files.writeString(config, configuration.toString(), StandardCharsets.UTF_8);
        Process remote = new ProcessBuilder(core.toString(), "run", "-c", config.toString())
                .redirectErrorStream(true).start();
        AtomicBoolean ready = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = remote.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.contains("sing-box started")) ready.set(true);
                }
            } catch (IOException ignored) { /* Process shutdown closes the pipe. */ }
        }, "local-proxy-fixture-output");
        reader.setDaemon(true);
        readers.put(remote, reader);
        reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && remote.isAlive()) {
                if (ready.get()) return remote;
                Thread.sleep(25);
            }
            fail("Local " + protocol + " server failed to become ready");
            return remote;
        } catch (Throwable failure) { stop(remote); throw failure; }
    }
    private String request(int localPort, int targetPort) throws IOException {
        URL target = URI.create("http://127.0.0.1:" + targetPort + "/").toURL();
        HttpURLConnection request = (HttpURLConnection) target.openConnection(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", localPort)));
        request.setConnectTimeout(3000); request.setReadTimeout(5000);
        try { return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
        finally { request.disconnect(); }
    }
    private HttpServer target(String payload) throws IOException {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        target.start(); return target;
    }
    private void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture process did not exit");
            }
        }
        Thread reader = readers.remove(process);
        if (reader != null) {
            reader.join(1000);
            assertFalse(reader.isAlive(), "Fixture output reader did not finish");
        }
    }
    @ParameterizedTest(name = "encrypted local {0} round trip")
    @ValueSource(strings = {"vless", "trojan", "hysteria2", "tuic"})
    void encryptedRoundTripWithVerifiedLocalCertificate(String protocol) throws Exception {
        int remotePort = freeRemotePort(protocol), localPort = freeTcpPort();
        String payload = "encrypted-local-round-trip-" + protocol;
        HttpServer target = target(payload);
        Process remote = null;
        try {
            remote = startRemote(protocol, remotePort);
            try (ProxyEngine client = new ProxyEngine(core, directory.resolve("client"))) {
                client.start(Map.of(localPort, server(protocol, remotePort, true)), ignored -> { });
                assertEquals(payload, request(localPort, target.getAddress().getPort()));
                assertTrue(client.alive()); assertTrue(remote.isAlive());
            }
            ProxyEngine.ensurePortFree(localPort);
            try (var files = Files.list(directory.resolve("client"))) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("session-")), "Client session files must be removed");
            }
        } finally {
            try { if (remote != null) stop(remote); } finally { target.stop(0); }
        }
    }
    @Test void untrustedTrojanCertificateIsRejected() throws Exception {
        int remotePort = freeRemotePort("trojan"), localPort = freeTcpPort();
        AtomicInteger requests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        target.start(); Process remote = null;
        try {
            remote = startRemote("trojan", remotePort);
            try (ProxyEngine client = new ProxyEngine(core, directory.resolve("untrusted-client"))) {
                client.start(Map.of(localPort, server("trojan", remotePort, false)), ignored -> { });
                assertThrows(IOException.class, () -> request(localPort, target.getAddress().getPort()));
                assertEquals(0, requests.get(), "Untrusted TLS must fail before the target receives any request");
            }
        } finally {
            try { if (remote != null) stop(remote); } finally { target.stop(0); }
        }
    }
}
