package xyz.shadowmere.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reversible, per-user system HTTP proxy integration. No elevation is attempted. */
public final class SystemProxy implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String REGISTRY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static final String[] WINDOWS_KEYS = {
        "ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"
    };
    private static final String REFRESH = """
        Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices;
        public static class ShadowmereWinInet {
          [DllImport("wininet.dll", SetLastError=true)]
          public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int n);
        }';
        $changed = [ShadowmereWinInet]::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0);
        $refreshed = [ShadowmereWinInet]::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0);
        if (!$changed -or !$refreshed) { throw 'Windows could not refresh its proxy settings.' }
        """;
    private final Path snapshotFile;
    private final String platform;

    public SystemProxy(Path dataDir) {
        snapshotFile = dataDir.toAbsolutePath().resolve("system-proxy-recovery.json");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        platform = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : "unsupported";
    }

    public boolean isSupported() { return !platform.equals("unsupported"); }
    public synchronized boolean hasRecovery() { return Files.exists(snapshotFile); }

    /** Saves previous settings durably before the first operating-system change. */
    public synchronized void enable(String host, int port) throws Exception {
        if (!isSupported()) {
            throw new IOException("Automatic system proxy is available on macOS and Windows. "
                + "Configure your browser to use the local HTTP or SOCKS proxy instead.");
        }
        if (host == null || !host.matches("[A-Za-z0-9.:\\-]+") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid local proxy address.");
        }
        // Never replace an outstanding recovery snapshot with our own proxy settings.
        if (hasRecovery()) restore();
        JsonObject snapshot = platform.equals("macos") ? snapshotMac() : snapshotWindows();
        snapshot.addProperty("platform", platform);
        snapshot.addProperty("version", 1);
        save(snapshot);
        try {
            if (platform.equals("macos")) {
                String service = snapshot.get("service").getAsString();
                mac("-setwebproxy", service, host, Integer.toString(port));
                mac("-setsecurewebproxy", service, host, Integer.toString(port));
                mac("-setwebproxystate", service, "on");
                mac("-setsecurewebproxystate", service, "on");
                mac("-setsocksfirewallproxystate", service, "off");
                mac("-setautoproxystate", service, "off");
                mac("-setproxyautodiscovery", service, "off");
            } else {
                String address = host.contains(":") ? "[" + host + "]" : host;
                powershell(openRegistry() + """
                    $k.SetValue('ProxyEnable',1,[Microsoft.Win32.RegistryValueKind]::DWord);
                    $k.SetValue('ProxyServer','http=%s:%d;https=%s:%d',[Microsoft.Win32.RegistryValueKind]::String);
                    $k.SetValue('ProxyOverride','<local>;localhost;127.*;[::1]',[Microsoft.Win32.RegistryValueKind]::String);
                    $k.DeleteValue('AutoConfigURL',$false);
                    $k.SetValue('AutoDetect',0,[Microsoft.Win32.RegistryValueKind]::DWord);
                    $k.Close();
                    """.formatted(address, port, address, port) + REFRESH);
            }
        } catch (Exception failure) {
            // Process waits reassert interruption. Let rollback finish before returning
            // cancellation to the controller, otherwise every restore command aborts.
            boolean interrupted = Thread.interrupted();
            try {
                try { restore(); } catch (Exception recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException) {
                throw failure;
            }
            throw new IOException("Could not enable the system proxy. Check your network settings permissions. "
                + (hasRecovery() ? "Previous settings are saved for recovery on the next launch." : "Previous settings were restored."), failure);
        }
    }

    /** Also restores a snapshot left by an interrupted previous application run. */
    public synchronized void restore() throws Exception {
        if (!hasRecovery()) return;
        JsonObject snapshot;
        try {
            if (Files.size(snapshotFile) > 262_144) throw new IOException("Recovery snapshot is too large.");
            snapshot = JSON.fromJson(Files.readString(snapshotFile), JsonObject.class);
            if (snapshot == null || !snapshot.has("version") || snapshot.get("version").getAsInt() != 1
                || !platform.equals(snapshot.get("platform").getAsString()) || !isSupported()) {
                throw new IOException("Recovery snapshot belongs to a different platform or version.");
            }
            if (platform.equals("macos")) restoreMac(snapshot);
            else restoreWindows(snapshot);
        } catch (Exception failure) {
            throw new IOException("Could not restore previous system proxy settings. The recovery snapshot was retained. "
                + "Check network settings permissions and try again.", failure);
        }
        Files.delete(snapshotFile);
    }

    @Override public void close() throws Exception { restore(); }

    private JsonObject snapshotMac() throws Exception {
        String active = null;
        for (String line : mac("-listallnetworkservices").split("\\R")) {
            String service = line.strip();
            if (service.isEmpty() || service.startsWith("*") || service.startsWith("An asterisk")) continue;
            String info = mac("-getinfo", service);
            for (String field : info.split("\\R")) {
                if (field.startsWith("IP address: ") && !field.substring(12).strip().equalsIgnoreCase("none")
                    && !field.substring(12).isBlank()) { active = service; break; }
            }
            if (active != null) break;
        }
        if (active == null) throw new IOException("No active macOS network service was found. Connect to Wi-Fi or Ethernet first.");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("service", active);
        snapshot.add("web", proxyFields(mac("-getwebproxy", active)));
        snapshot.add("secure", proxyFields(mac("-getsecurewebproxy", active)));
        snapshot.add("socks", proxyFields(mac("-getsocksfirewallproxy", active)));
        snapshot.add("pac", proxyFields(mac("-getautoproxyurl", active)));
        snapshot.add("discovery", proxyFields(mac("-getproxyautodiscovery", active)));
        // networksetup cannot read an existing proxy password back from Keychain.
        for (String key : List.of("web", "secure")) {
            JsonObject proxy = snapshot.getAsJsonObject(key);
            if (proxy.has("Authenticated Proxy Enabled") && proxy.get("Authenticated Proxy Enabled").getAsString().equals("1")) {
                throw new IOException("An authenticated macOS proxy is already configured. Use the local proxy in your browser "
                    + "to preserve its saved credentials.");
            }
            require(proxy, "Enabled", "Server", "Port");
        }
        require(snapshot.getAsJsonObject("socks"), "Enabled");
        require(snapshot.getAsJsonObject("pac"), "Enabled");
        require(snapshot.getAsJsonObject("discovery"), "Auto Proxy Discovery");
        return snapshot;
    }

    private void restoreMac(JsonObject snapshot) throws Exception {
        String service = snapshot.get("service").getAsString();
        List<Exception> failures = new ArrayList<>();
        for (String key : List.of("web", "secure")) {
            JsonObject p = snapshot.getAsJsonObject(key);
            String type = key.equals("web") ? "web" : "secureweb";
            attempt(failures, () -> mac("-set" + type + "proxy", service,
                p.get("Server").getAsString(), p.get("Port").getAsString()));
            attempt(failures, () -> mac("-set" + type + "proxystate", service, state(p, "Enabled")));
        }
        // Their address / URL were never changed, so restore only their enable flags.
        attempt(failures, () -> mac("-setsocksfirewallproxystate", service, state(snapshot.getAsJsonObject("socks"), "Enabled")));
        attempt(failures, () -> mac("-setautoproxystate", service, state(snapshot.getAsJsonObject("pac"), "Enabled")));
        attempt(failures, () -> mac("-setproxyautodiscovery", service, state(snapshot.getAsJsonObject("discovery"), "Auto Proxy Discovery")));
        if (!failures.isEmpty()) {
            IOException error = new IOException("macOS could not restore all proxy settings.");
            failures.forEach(error::addSuppressed);
            throw error;
        }
    }

    private JsonObject snapshotWindows() throws Exception {
        String script = openRegistry() + "$result = [ordered]@{}; $names = $k.GetValueNames();\n";
        for (String key : WINDOWS_KEYS) {
            script += "if ($names -contains '" + key + "') { $result['" + key + "'] = @{present=$true;kind=$k.GetValueKind('"
                + key + "').ToString();value=$k.GetValue('" + key + "',$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)} } "
                + "else { $result['" + key + "'] = @{present=$false} };\n";
        }
        script += "$k.Close(); $result | ConvertTo-Json -Depth 8 -Compress;";
        JsonObject snapshot = new JsonObject();
        snapshot.add("values", JSON.fromJson(powershell(script).strip(), JsonObject.class));
        return snapshot;
    }

    private void restoreWindows(JsonObject snapshot) throws Exception {
        // Base64 JSON keeps all user-controlled strings out of executable PowerShell syntax.
        String encoded = Base64.getEncoder().encodeToString(JSON.toJson(snapshot.getAsJsonObject("values")).getBytes(StandardCharsets.UTF_8));
        powershell(openRegistry() + "$s = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + encoded + "')) | ConvertFrom-Json;\n" + """
            foreach ($p in $s.PSObject.Properties) {
                $v = $p.Value;
                if (!$v.present) { $k.DeleteValue($p.Name,$false); continue }
                $kind = [Microsoft.Win32.RegistryValueKind]([Enum]::Parse([Microsoft.Win32.RegistryValueKind],$v.kind));
                $value = $v.value;
                switch ($v.kind) {
                    'DWord' { $value = [int]$value }
                    'QWord' { $value = [long]$value }
                    'Binary' { $value = [byte[]]$value }
                    'MultiString' { $value = [string[]]$value }
                    'String' { $value = [string]$value }
                    'ExpandString' { $value = [string]$value }
                    default { throw 'Unsupported saved registry type.' }
                }
                $k.SetValue($p.Name,$value,$kind);
            }
            $k.Close();
            """ + REFRESH);
    }

    private static String openRegistry() {
        return "$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('" + REGISTRY + "',$true); "
            + "if ($null -eq $k) { throw 'Windows Internet Settings is unavailable.' };\n";
    }

    private void save(JsonObject snapshot) throws IOException {
        Files.createDirectories(snapshotFile.getParent());
        Path temp;
        try {
            temp = Files.createTempFile(snapshotFile.getParent(), ".proxy-recovery-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            temp = Files.createTempFile(snapshotFile.getParent(), ".proxy-recovery-", ".tmp");
        }
        try {
            byte[] bytes = JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temp, snapshotFile, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException failure) {
                // No OS state was touched; refusing is safer than leaving an incomplete recovery file.
                throw new IOException("This data directory cannot safely save proxy recovery settings.", failure);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static JsonObject proxyFields(String output) {
        JsonObject fields = new JsonObject();
        for (String line : output.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon >= 0) fields.addProperty(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
        return fields;
    }

    private static void require(JsonObject object, String... names) throws IOException {
        for (String name : names) if (!object.has(name)) throw new IOException("Could not read current macOS proxy settings.");
    }

    private static String state(JsonObject object, String key) {
        String value = object.get(key).getAsString();
        return value.equalsIgnoreCase("Yes") || value.equalsIgnoreCase("On") || value.equals("1") ? "on" : "off";
    }

    private static String mac(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("/usr/sbin/networksetup");
        command.addAll(List.of(args));
        return run(command, "macOS network settings");
    }

    private static String powershell(String script) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(("$ErrorActionPreference='Stop'; "
            + "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); " + script).getBytes(StandardCharsets.UTF_16LE));
        String windows = System.getenv("SystemRoot");
        if (windows == null || windows.isBlank()) throw new IOException("Windows system directory is unavailable.");
        String executable = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        return run(List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), "Windows network settings");
    }

    private static String run(List<String> command, String description) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(65_536);
                if (bytes.length == 65_536) {
                    process.destroyForcibly();
                    throw new IOException("Network settings output exceeded its size limit.");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(description + " timed out. Check network settings permissions.");
            }
            String text = output.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || text.contains("Error:") || text.contains("** Error")) {
                // Platform output may contain private PAC URLs or credentials: never expose it in errors.
                throw new IOException(description + " failed (exit " + process.exitValue() + "). Check permissions.");
            }
            return text;
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            // Wait for termination before rollback can issue conflicting settings
            // commands. This cleanup has its own short deadline, even on cancellation.
            if (process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                try {
                    while (process.isAlive()) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) break;
                        try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            output.cancel(true);
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void attempt(List<Exception> failures, Operation operation) {
        try { operation.run(); } catch (Exception failure) { failures.add(failure); }
    }
}
