package xyz.moondrop.android;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Exercises the real JNI/core and encrypted transport without remote servers. */
@RunWith(AndroidJUnit4.class)
public final class NativeEngineTest {
    @After public void cleanup() { NativeEngine.stop(); }

    @Test public void encryptedShadowsocksRoundTripSurvivesStopAndRestart() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int mixedPort = availablePort();
        int encryptedPort = availablePort();
        while (mixedPort == encryptedPort) encryptedPort = availablePort();
        String config = loopbackConfig(mixedPort, encryptedPort);
        ExecutorService fixtureWorker = Executors.newSingleThreadExecutor();
        try (ServerSocket target = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            target.setSoTimeout(10000);
            for (int cycle = 0; cycle < 2; cycle++) {
                String token = "moondrop-encrypted-" + UUID.randomUUID();
                Future<?> fixture = fixtureWorker.submit(() -> serve(target, token));
                NativeEngine.start(context, null, config);
                try {
                    String response = request(mixedPort, target.getLocalPort(), token);
                    assertTrue("HTTP fixture response missing", response.startsWith("HTTP/1.1 200"));
                    assertTrue("Encrypted proxy did not deliver fixture response", response.endsWith(token));
                    fixture.get(10, TimeUnit.SECONDS);
                } finally { NativeEngine.stop(); }
                assertListenerClosed(mixedPort);
                assertListenerClosed(encryptedPort);
            }
        } finally { fixtureWorker.shutdownNow(); }
    }

    @Test public void invalidConfigurationDoesNotLeaveEngineRunning() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            NativeEngine.start(context, null, "{invalid json");
            fail("Invalid configuration was accepted");
        } catch (Exception expected) {
            // A failed start must release both native and Java ownership.
        }
        int mixedPort = availablePort();
        int encryptedPort = availablePort();
        while (mixedPort == encryptedPort) encryptedPort = availablePort();
        NativeEngine.start(context, null, loopbackConfig(mixedPort, encryptedPort));
        NativeEngine.stop();
        assertListenerClosed(mixedPort);
        assertListenerClosed(encryptedPort);
    }

    private static String loopbackConfig(int mixedPort, int encryptedPort) {
        JsonObject root = new JsonObject();
        JsonObject log = new JsonObject(); log.addProperty("disabled", true); root.add("log", log);
        JsonArray inbounds = new JsonArray();
        JsonObject mixed = new JsonObject();
        mixed.addProperty("type", "mixed"); mixed.addProperty("tag", "mixed-in");
        mixed.addProperty("listen", "127.0.0.1"); mixed.addProperty("listen_port", mixedPort);
        inbounds.add(mixed);
        JsonObject encrypted = new JsonObject();
        encrypted.addProperty("type", "shadowsocks"); encrypted.addProperty("tag", "ss-in");
        encrypted.addProperty("listen", "127.0.0.1"); encrypted.addProperty("listen_port", encryptedPort);
        encrypted.addProperty("method", "aes-128-gcm"); encrypted.addProperty("password", "moondrop-local-test-only");
        inbounds.add(encrypted); root.add("inbounds", inbounds);
        JsonArray outbounds = new JsonArray();
        JsonObject remote = new JsonObject(); remote.addProperty("type", "shadowsocks");
        remote.addProperty("tag", "encrypted"); remote.addProperty("server", "127.0.0.1");
        remote.addProperty("server_port", encryptedPort); remote.addProperty("method", "aes-128-gcm");
        remote.addProperty("password", "moondrop-local-test-only"); outbounds.add(remote);
        JsonObject direct = new JsonObject(); direct.addProperty("type", "direct"); direct.addProperty("tag", "direct");
        outbounds.add(direct); root.add("outbounds", outbounds);
        JsonObject route = new JsonObject(); route.addProperty("final", "direct");
        JsonArray rules = new JsonArray(); rules.add(rule("mixed-in", "encrypted")); rules.add(rule("ss-in", "direct"));
        route.add("rules", rules); root.add("route", route);
        return root.toString();
    }

    private static JsonObject rule(String inbound, String outbound) {
        JsonObject result = new JsonObject(); JsonArray tags = new JsonArray(); tags.add(inbound);
        result.add("inbound", tags); result.addProperty("outbound", outbound); return result;
    }

    private static String request(int proxyPort, int targetPort, String token) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), 3000); socket.setSoTimeout(10000);
            String request = "GET http://127.0.0.1:" + targetPort + "/encrypted-test HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + targetPort + "\r\nX-Moondrop-Test: " + token + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static void serve(ServerSocket server, String token) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String first = reader.readLine();
            if (first == null || !first.contains("/encrypted-test")) throw new AssertionError("Unexpected HTTP fixture path");
            boolean tokenSeen = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty())
                if (line.equalsIgnoreCase("X-Moondrop-Test: " + token)) tokenSeen = true;
            if (!tokenSeen) throw new AssertionError("Encrypted request lost its unique test token");
            String response = "HTTP/1.1 200 OK\r\nContent-Length: " + token.length() + "\r\nConnection: close\r\n\r\n" + token;
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception failure) { throw new AssertionError("Loopback HTTP fixture failed", failure); }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private static void assertListenerClosed(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 100); }
            catch (java.net.ConnectException expected) { return; }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Native listener was left open on port " + port);
    }
}
