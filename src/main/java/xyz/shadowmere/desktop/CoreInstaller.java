package xyz.shadowmere.desktop;

import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

public final class CoreInstaller {
    public static final String VERSION = "1.14.3";
    private static final Map<String,String> HASHES = Map.of(
        "darwin-amd64", "6d002b74d03b478547349cc10af89e007868596e5aa931816d45da9f544715a9",
        "darwin-arm64", "0360ce634a04c26a6b4fd957517987bb4f6c8950f63435ce3c5af2fcae5860c5",
        "windows-amd64", "d8b86ef58936dba9230ec6a57ffa4243933f3c14ef4809828b6c553440ed1518",
        "windows-arm64", "195b35424c6e3f5913954c7f08c5d8ba2d2199f6d18eb27a3972a5a29cdaa78c",
        "linux-amd64", "e2bdf179c15a3652955dc44867e33ca0965c9c9b91b34267dcc5a5d639a5feee");
    private final Path directory;
    public CoreInstaller(Path dataDir) { directory = dataDir.resolve("engine"); }
    public Path binary() { return directory.resolve(isWindows() ? "sing-box.exe" : "sing-box"); }
    public boolean ready() { return Files.isRegularFile(binary()) && (isWindows() || Files.isExecutable(binary())); }

    public void install(Consumer<String> progress) throws Exception {
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        String hash = HASHES.get(platform);
        if (hash == null) throw new IOException("This platform is not packaged yet: " + platform);
        Files.createDirectories(directory);
        String extension = platform.startsWith("windows") ? ".zip" : ".tar.gz";
        String name = "sing-box-" + VERSION + "-" + platform + extension;
        URI source = URI.create("https://github.com/SagerNet/sing-box/releases/download/v" + VERSION + "/" + name);
        Path archive = Files.createTempFile(directory, "download-", extension);
        Path extracted = Files.createTempFile(directory, "engine-", ".tmp");
        try {
            progress.accept("Downloading engine " + VERSION + " for " + platform + "…");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            var response = client.send(HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() != 200) throw new IOException("Engine download returned HTTP " + response.statusCode());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(archive)) { byte[] bytes = new byte[65536]; int n; while ((n = in.read(bytes)) >= 0) digest.update(bytes,0,n); }
            if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) throw new IOException("Engine checksum did not match. Download was discarded.");
            progress.accept("Checksum verified. Installing engine…");
            boolean found = false;
            if (extension.equals(".zip")) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                    ZipEntry e; while ((e = zip.getNextEntry()) != null) if (!e.isDirectory() && (e.getName().equals("sing-box.exe") || e.getName().endsWith("/sing-box.exe"))) {
                        Files.copy(zip, extracted, StandardCopyOption.REPLACE_EXISTING); found = true; break;
                    }
                }
            } else {
                try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
                    TarArchiveEntry e; while ((e = tar.getNextEntry()) != null) if (e.isFile() && !e.isLink() && (e.getName().equals("sing-box") || e.getName().endsWith("/sing-box"))) {
                        Files.copy(tar, extracted, StandardCopyOption.REPLACE_EXISTING); found = true; break;
                    }
                }
            }
            if (!found) throw new IOException("Engine binary is missing from the archive.");
            if (!isWindows() && !extracted.toFile().setExecutable(true, true)) throw new IOException("Cannot make the engine executable.");
            Files.move(extracted, binary(), StandardCopyOption.REPLACE_EXISTING);
            progress.accept("Engine ready · sing-box " + VERSION);
        } finally { Files.deleteIfExists(archive); Files.deleteIfExists(extracted); }
    }

    static String platform(String os, String arch) {
        String system = os.toLowerCase(Locale.ROOT);
        system = system.contains("mac") ? "darwin" : system.contains("win") ? "windows" : system.contains("linux") ? "linux" : system;
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) { case "aarch64", "arm64" -> "arm64"; case "amd64", "x86_64" -> "amd64"; default -> arch; };
        return system + "-" + cpu;
    }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"); }
}
