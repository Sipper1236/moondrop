package xyz.shadowmere.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Offline native schema checks: no listener is started and no endpoint is contacted. */
class NativeProtocolsTest {
    @TempDir Path directory;
    private static final String UUID = "bf000d23-0752-40b4-affe-68f7707a9661";
    private static final String KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0";

    static Stream<String> secureProfiles() {
        return Stream.of(
                "vless://" + UUID + "@1.1.1.1:443?security=tls",
                "vless://" + UUID + "@1.1.1.1:443?security=tls&type=ws&host=cdn.example.org&path=%2Fproxy",
                "vless://" + UUID + "@1.1.1.1:443?security=tls&type=grpc&serviceName=proxy",
                "vless://" + UUID + "@1.1.1.1:443?security=tls&type=httpupgrade&host=cdn.example.org&path=%2Fproxy",
                "vless://" + UUID + "@1.1.1.1:443?security=reality&pbk=" + KEY + "&sid=0123456789abcdef&sni=www.example.org&flow=xtls-rprx-vision&fp=chrome",
                "trojan://test-only-password@1.1.1.1:443?sni=certificate.example.org&type=ws&path=%2Fproxy",
                "hy2://test-only-password@1.1.1.1:443?sni=certificate.example.org&obfs=salamander&obfs-password=test-only-obfuscation",
                "tuic://" + UUID + ":test-only-password@1.1.1.1:443?sni=certificate.example.org&congestion_control=bbr&udp_relay_mode=native&alpn=h3");
    }

    @ParameterizedTest(name = "secure native profile {index}")
    @MethodSource("secureProfiles")
    void nativeCoreAcceptsGeneratedSecureProfile(String uri) throws Exception {
        String core = System.getProperty("engine.test.path", "");
        Assumptions.assumeTrue(!core.isEmpty() && Files.isExecutable(Path.of(core)),
                "Set -Dengine.test.path for offline native sing-box checks");
        Server server = ProxyLinks.parse("native-test", uri, "", "Native test", "", true);
        var outbound = server.outbound();
        assertEquals(server.protocol(), outbound.get("type").getAsString());
        assertTrue(outbound.getAsJsonObject("tls").get("enabled").getAsBoolean());
        assertFalse(outbound.getAsJsonObject("tls").get("insecure").getAsBoolean());
        Path config = directory.resolve("native-protocol.json");
        Files.writeString(config, ProxyEngine.configuration(Map.of(18080, server)).toString(), StandardCharsets.UTF_8);
        Path output = directory.resolve("native-check.log");
        Process process = new ProcessBuilder(core, "check", "-c", config.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Native config check exceeded ten seconds");
            // Report schema errors without reproducing credential-bearing share links or JSON.
            assertEquals(0, process.exitValue(), "Native core rejected " + server.protocolName() + " configuration");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }
}
