package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Each instance owns exactly one child process and its private, short-lived configuration. */
public final class ProxyEngine implements AutoCloseable {
    public static final int PORT = 17890;
    private final Path binary, directory;
    private Process process;
    private Path config;
    public ProxyEngine(Path binary, Path dataDir) { this.binary = binary; directory = dataDir; }

    static JsonObject configuration(Map<Integer,Server> bindings) {
        JsonObject root = new JsonObject();
        root.add("log", new Gson().toJsonTree(Map.of("level", "error", "timestamp", true)));
        JsonArray inbounds = new JsonArray(), outbounds = new JsonArray(), rules = new JsonArray();
        int i = 0;
        for (var binding : bindings.entrySet()) {
            String tag = "node-" + i++, inbound = "in-" + tag;
            inbounds.add(new Gson().toJsonTree(Map.of("type", "mixed", "tag", inbound, "listen", "127.0.0.1", "listen_port", binding.getKey())));
            Server s = binding.getValue();
            outbounds.add(new Gson().toJsonTree(Map.of("type", "shadowsocks", "tag", tag, "server", s.host(),
                    "server_port", s.port(), "method", s.method(), "password", s.password())));
            rules.add(new Gson().toJsonTree(Map.of("inbound", List.of(inbound), "outbound", tag, "action", "route")));
        }
        root.add("inbounds", inbounds); root.add("outbounds", outbounds);
        root.add("route", new Gson().toJsonTree(Map.of("rules", rules, "final", "node-0")));
        return root;
    }

    public synchronized void start(Map<Integer,Server> bindings, Consumer<String> logs) throws Exception {
        if (process != null) throw new IllegalStateException("Engine already running");
        if (bindings.isEmpty()) throw new IllegalArgumentException("No server selected");
        Files.createDirectories(directory);
        try {
            try { config = Files.createTempFile(directory,"session-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException e) { config = Files.createTempFile(directory,"session-", ".json"); }
            Files.writeString(config, configuration(bindings).toString(), StandardCharsets.UTF_8);
            process = new ProcessBuilder(binary.toString(), "run", "-c", config.toString()).redirectErrorStream(true).start();
            Process child = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = child.inputReader()) { String line; int count = 0;
                    while ((line = in.readLine()) != null) if (++count <= 50) logs.accept("Engine: " + line.replaceAll("\\u001B\\[[;\\d]*m", ""));
                } catch (IOException ignored) { }
            }, "engine-output"); reader.setDaemon(true); reader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (!child.isAlive()) throw new IOException("The engine could not start. Check the activity log or choose another server.");
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", bindings.keySet().iterator().next()), 100);
                    return;
                } catch (IOException e) { Thread.sleep(60); }
            }
            throw new IOException("The local proxy did not become ready.");
        } catch (Exception e) { close(); throw e; }
    }

    public synchronized boolean alive() { return process != null && process.isAlive(); }

    /** Verifies the complete proxy path, including HTTPS certificate verification. */
    public static long probe(int port) throws IOException {
        long started = System.nanoTime();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
        HttpURLConnection connection = (HttpURLConnection) URI.create("https://www.gstatic.com/generate_204").toURL().openConnection(proxy);
        connection.setConnectTimeout(4500); connection.setReadTimeout(4500); connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 204) throw new IOException("The server did not pass the HTTPS connection check.");
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        } finally { connection.disconnect(); }
    }

    public static void ensurePortFree(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
        catch (IOException e) { throw new IOException("Port " + port + " is in use. Close the other local proxy and try again."); }
    }

    /** Reaps only cores whose executable AND private session path identify this app's data directory. */
    public static void recover(Path binary,Path directory) throws Exception {
        Path expectedDirectory=directory.toAbsolutePath().normalize();
        if(!Files.isRegularFile(binary)) return;
        Path expectedBinary=binary.toRealPath();
        for(ProcessHandle process:ProcessHandle.allProcesses().toList()) {
            ProcessHandle.Info info=process.info();
            if(info.command().isEmpty()||info.arguments().isEmpty())continue;
            Path command;
            try {command=Path.of(info.command().get()).toRealPath();}catch(IOException e){continue;}
            if(!command.equals(expectedBinary))continue;
            String[] args=info.arguments().get();
            for(int i=0;i<args.length-1;i++) if(args[i].equals("-c")) {
                Path session=Path.of(args[i+1]).toAbsolutePath().normalize();
                String name=session.getFileName().toString();
                if(!expectedDirectory.equals(session.getParent())||!name.startsWith("session-")||!name.endsWith(".json"))continue;
                process.destroy();
                try {process.onExit().get(2,TimeUnit.SECONDS);}
                catch(TimeoutException e) {process.destroyForcibly();process.onExit().get(2,TimeUnit.SECONDS);}
                Files.deleteIfExists(session);
            }
        }
    }

    @Override public synchronized void close() {
        if (process != null) {
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); } }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            process = null;
        }
        if (config != null) { try { Files.deleteIfExists(config); } catch (IOException ignored) { } config = null; }
    }
}
