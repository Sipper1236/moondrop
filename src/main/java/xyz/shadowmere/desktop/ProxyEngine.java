package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Each instance owns exactly one child process and its private, short-lived configuration. */
public final class ProxyEngine implements AutoCloseable {
    public static final int PORT = 17890;
    private final Path binary, directory;
    private Process process;
    private Path config, ownership;
    private record Ownership(int version, long pid, String started, String executable, String config) { }
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
            JsonObject outbound=PublicEndpoints.outbound(s);outbound.addProperty("tag",tag);outbounds.add(outbound);
            rules.add(new Gson().toJsonTree(Map.of("inbound", List.of(inbound), "outbound", tag, "action", "route")));
        }
        root.add("inbounds", inbounds); root.add("outbounds", outbounds);
        root.add("route", new Gson().toJsonTree(Map.of("rules", rules, "final", "node-0")));
        return root;
    }

    static JsonObject configurationChain(Server entry, Server exit) { return configurationChain(PORT, entry, exit); }

    static JsonObject configurationChain(int listenerPort, Server entry, Server exit) {
        if (entry.host().equalsIgnoreCase(exit.host()) && entry.port() == exit.port())
            throw new IllegalArgumentException("Choose two different proxy endpoints for two-hop routing.");
        JsonObject root = configuration(Map.of(listenerPort, exit));
        JsonArray outbounds = root.getAsJsonArray("outbounds");
        outbounds.get(0).getAsJsonObject().addProperty("detour", "chain-entry");
        JsonObject first = PublicEndpoints.outbound(entry);
        if (first.get("server").getAsString().equals(outbounds.get(0).getAsJsonObject().get("server").getAsString()) && entry.port() == exit.port())
            throw new IllegalArgumentException("The entry and exit resolve to the same proxy endpoint.");
        first.addProperty("tag", "chain-entry");
        outbounds.add(first);
        return root;
    }

    public synchronized void startChain(Server entry, Server exit, Consumer<String> logs) throws Exception {
        startChain(PORT, entry, exit, logs);
    }

    public synchronized void startChain(int listenerPort, Server entry, Server exit, Consumer<String> logs) throws Exception {
        validPort(listenerPort);
        startConfiguration(configurationChain(listenerPort, entry, exit), listenerPort, logs);
    }

    /** Tests a new candidate through the established local SOCKS listener. The only
     * direct socket owned by this temporary engine is to loopback; no direct fallback exists. */
    public synchronized void startViaLocalProxy(int listenerPort, Server exit, int establishedProxyPort,
                                                Consumer<String> logs) throws Exception {
        startConfiguration(configurationViaLocalProxy(listenerPort, exit, establishedProxyPort), listenerPort, logs);
    }

    static JsonObject configurationViaLocalProxy(int listenerPort, Server exit, int establishedProxyPort) {
        validPort(listenerPort);validPort(establishedProxyPort);
        if(listenerPort==establishedProxyPort)throw new IllegalArgumentException("The test listener must differ from the established proxy listener.");
        JsonObject root=configuration(Map.of(listenerPort,exit));
        JsonArray outbounds=root.getAsJsonArray("outbounds");
        outbounds.get(0).getAsJsonObject().addProperty("detour","established-local-proxy");
        JsonObject entry=new JsonObject();
        entry.addProperty("type","socks");entry.addProperty("tag","established-local-proxy");
        entry.addProperty("server","127.0.0.1");entry.addProperty("server_port",establishedProxyPort);entry.addProperty("version","5");
        outbounds.add(entry);
        return root;
    }
    private static void validPort(int port) {
        if(port<1 || port>65535)throw new IllegalArgumentException("Invalid local proxy listener port.");
    }

    public synchronized void start(Map<Integer,Server> bindings, Consumer<String> logs) throws Exception {
        if (bindings.isEmpty()) throw new IllegalArgumentException("No server selected");
        startConfiguration(configuration(bindings), bindings.keySet().iterator().next(), logs);
    }

    private void startConfiguration(JsonObject settings, int listenerPort, Consumer<String> logs) throws Exception {
        if (process != null) throw new IllegalStateException("Engine already running");
        Files.createDirectories(directory);
        try {
            try { config = Files.createTempFile(directory.toRealPath(),"session-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException e) { config = Files.createTempFile(directory.toRealPath(),"session-", ".json"); }
            Files.writeString(config, settings.toString(), StandardCharsets.UTF_8);
            Path executable = binary.toRealPath();
            process = new ProcessBuilder(executable.toString(), "run", "-c", config.toString()).redirectErrorStream(true).start();
            Process child = process;
            saveOwnership(child, executable);
            Thread reader = new Thread(() -> {
                try (BufferedReader in = child.inputReader()) { String line; int count = 0;
                    while ((line = in.readLine()) != null) if (++count <= 50) logs.accept("Engine reported a connection or configuration error. Choose another server if the connection fails.");
                } catch (IOException ignored) { }
            }, "engine-output"); reader.setDaemon(true); reader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (!child.isAlive()) throw new IOException("The engine could not start. Check the activity log or choose another server.");
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", listenerPort), 100);
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

    /** Checks for a live listener, not lingering TCP TIME_WAIT connections.
     * The core's bind is authoritative; this check never enables SO_REUSEADDR,
     * which could silently share an existing Windows listener.
     */
    public static void ensurePortFree(int port) throws IOException {
        // Windows may report the child exited before its kernel listener finishes
        // closing. Allow that brief teardown without accepting a persistent listener.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            try (Socket socket = new Socket()) {
                int timeout = (int) Math.max(1, Math.min(500,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                try { socket.connect(new InetSocketAddress("127.0.0.1", port), timeout); }
                catch (ConnectException refused) { return; }
                catch (SocketTimeoutException busy) { /* Check again until the deadline. */ }
                catch (IOException failure) { throw new IOException("Could not check local proxy port " + port + ".", failure); }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50))); }
            catch (InterruptedException cancellation) {
                Thread.currentThread().interrupt();
                CancellationException failure = new CancellationException("Local proxy port check was cancelled.");
                failure.initCause(cancellation);
                throw failure;
            }
        } while (System.nanoTime() < deadline);
        throw occupied(port);
    }

    private static IOException occupied(int port) {
        return new IOException("Port " + port + " is in use. Close the other local proxy and try again.");
    }

    private void saveOwnership(Process child, Path executable) throws IOException {
        ProcessHandle.Info info = child.info();
        Instant started = info.startInstant().orElseThrow(() -> new IOException("Could not identify the engine's start time safely."));
        String command = info.command().orElseThrow(() -> new IOException("Could not identify the engine executable safely."));
        if (!Path.of(command).toRealPath().equals(executable)) {
            throw new IOException("The launched engine executable could not be verified.");
        }
        Ownership record = new Ownership(1, child.pid(), started.toString(), executable.toString(), config.toString());
        Path metadata = config.resolveSibling(config.getFileName() + ".owner");
        Path temp = privateTemp(config.getParent(), ".engine-owner-", ".tmp");
        try {
            byte[] json = new Gson().toJson(record).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(json);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Refuse startup rather than leave an unrecoverable child if atomic
            // persistence is unavailable on the selected data filesystem.
            Files.move(temp, metadata, StandardCopyOption.ATOMIC_MOVE);
            ownership = metadata;
        } finally { Files.deleteIfExists(temp); }
    }

    private static Path privateTemp(Path directory, String prefix, String suffix) throws IOException {
        try { return Files.createTempFile(directory, prefix, suffix,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException ignored) { return Files.createTempFile(directory, prefix, suffix); }
    }

    /** Startup holds the instance lock. Kill only a recorded child with an exact
     * PID, start instant and canonical executable match; arguments are unavailable
     * from ProcessHandle on Windows and are deliberately not used.
     */
    public static void recover(Path binary, Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return;
        Path expectedDirectory = directory.toRealPath();
        Path expectedBinary = Files.isRegularFile(binary) ? binary.toRealPath() : null;
        List<Path> records;
        try (var files = Files.list(expectedDirectory)) {
            records = files.filter(p -> p.getFileName().toString().startsWith("session-")
                && p.getFileName().toString().endsWith(".json.owner")).toList();
        }
        for (Path metadata : records) {
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 16_384) {
                throw new IOException("An engine recovery record cannot be read safely.");
            }
            Ownership record;
            Path session, recordedExecutable;
            Instant started;
            try {
                record = new Gson().fromJson(Files.readString(metadata), Ownership.class);
                if (record == null || record.version() != 1 || record.pid() <= 0) throw new IllegalArgumentException();
                session = Path.of(record.config()).toAbsolutePath().normalize();
                recordedExecutable = Path.of(record.executable()).toAbsolutePath().normalize();
                started = Instant.parse(record.started());
                String name = session.getFileName().toString();
                if (!expectedDirectory.equals(session.getParent()) || !name.startsWith("session-")
                    || !name.endsWith(".json") || !metadata.getFileName().toString().equals(name + ".owner")) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IOException("An engine recovery record is invalid; no process was stopped.", invalid);
            }
            Optional<ProcessHandle> found = ProcessHandle.of(record.pid());
            if (found.isPresent() && found.get().isAlive()) {
                ProcessHandle child = found.get();
                ProcessHandle.Info info = child.info();
                // If inspection is unavailable, retain the record and let the user
                // resolve the process manually instead of guessing its identity.
                if (info.startInstant().isEmpty() || info.command().isEmpty()) {
                    throw new IOException("Cannot safely identify the previous engine. Close it before reconnecting.");
                }
                boolean sameStart = info.startInstant().get().equals(started);
                Path actualExecutable;
                try { actualExecutable = Path.of(info.command().get()).toRealPath(); }
                catch (IOException missing) { throw new IOException("Cannot verify the previous engine executable.", missing); }
                if (sameStart && expectedBinary != null && expectedBinary.equals(recordedExecutable)
                    && expectedBinary.equals(actualExecutable) && child.pid() != ProcessHandle.current().pid()) {
                    // Recheck the start instant at the last possible point to avoid PID reuse.
                    if (!child.info().startInstant().filter(started::equals).isPresent()) continue;
                    terminate(child);
                    if (child.isAlive()) throw new IOException("The previous engine could not be stopped. Try closing it manually.");
                } else if (sameStart && expectedBinary == null && recordedExecutable.equals(actualExecutable)) {
                    throw new IOException("The engine binary is missing. Restore it before recovering the previous session.");
                }
                // Any mismatch is a stale/foreign record, never permission to kill.
            }
            Files.deleteIfExists(metadata);
            Files.deleteIfExists(session);
        }
        // Atomic-write remnants contain no credentials and never identify a child.
        try (var files = Files.list(expectedDirectory)) {
            for (Path temp : files.filter(p -> p.getFileName().toString().startsWith(".engine-owner-")
                && p.getFileName().toString().endsWith(".tmp")).toList()) Files.deleteIfExists(temp);
        }
    }

    private static void terminate(ProcessHandle child) {
        boolean interrupted = Thread.interrupted();
        try {
            child.destroy();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (child.isAlive() && System.nanoTime() < deadline) {
                try { child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException cancellation) { interrupted = true; }
                catch (ExecutionException | TimeoutException ignored) { break; }
            }
            if (child.isAlive()) {
                child.destroyForcibly();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (child.isAlive() && System.nanoTime() < deadline) {
                    try { child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException cancellation) { interrupted = true; }
                    catch (ExecutionException | TimeoutException ignored) { break; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void close() {
        if (process != null) {
            terminate(process.toHandle());
            if (process.isAlive()) return; // Retain identity and recovery files if termination failed.
            process = null;
        }
        if (ownership != null) { try { Files.deleteIfExists(ownership); } catch (IOException ignored) { } ownership = null; }
        if (config != null) { try { Files.deleteIfExists(config); } catch (IOException ignored) { } config = null; }
    }
}
