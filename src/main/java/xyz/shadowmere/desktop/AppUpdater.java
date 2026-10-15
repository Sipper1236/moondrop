package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.*;

/** Downloads verified application updates to staging; never modifies the running installation. */
public final class AppUpdater {
    private static final String PREFIX = "https://github.com/Sipper1236/moondrop/releases/download/";
    private static final URI API = URI.create("https://api.github.com/repos/Sipper1236/moondrop/releases?per_page=20");
    private static final long MAX_JAR = 64L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private final Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    public record Release(String version, String name, URI page, URI download, String sha256) { }
    public AppUpdater(Path dataDir) { directory = dataDir.resolve("updates"); }
    public static String currentVersion() {
        String version = AppUpdater.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "0.6.0" : version;
    }

    public Optional<Release> check(String currentVersion) throws Exception {
        try {
            JsonArray releases = JsonParser.parseString(new String(fetch(API, 2 * 1024 * 1024, 12), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
            List<Map.Entry<Release,JsonObject>> candidates = new ArrayList<>();
            for (JsonElement element : releases) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                parseRelease(object).filter(release -> compareVersions(release.version(), currentVersion) > 0)
                    .ifPresent(release -> candidates.add(Map.entry(release, object)));
            }
            candidates.sort((a, b) -> compareVersions(b.getKey().version(), a.getKey().version()));
            for (var candidate : candidates) {
                Release release = candidate.getKey();
                if (!release.sha256().isEmpty()) return Optional.of(release);
                String tag = candidate.getValue().get("tag_name").getAsString();
                for (JsonElement assetElement : candidate.getValue().getAsJsonArray("assets")) {
                    if (!assetElement.isJsonObject()) continue;
                    JsonObject asset = assetElement.getAsJsonObject();
                    if (!"SHA256SUMS".equals(string(asset, "name"))) continue;
                    URI sums = trustedAsset(string(asset, "browser_download_url"), tag, "SHA256SUMS");
                    if (sums == null) continue;
                    String checksum = checksum(new String(fetch(sums, 16384, 12), java.nio.charset.StandardCharsets.UTF_8));
                    if (checksum != null) return Optional.of(new Release(release.version(), release.name(), release.page(), release.download(), checksum));
                }
            }
            if (!candidates.isEmpty()) throw new IOException("The update has no verified checksum. Please try again after the release is ready.");
            return Optional.empty();
        } catch (InterruptedException cancellation) { Thread.currentThread().interrupt(); throw cancellation; }
        catch (IOException e) { throw friendly(e, "Could not check for updates. Check your internet connection and try again."); }
        catch (RuntimeException invalid) { throw new IOException("GitHub returned an invalid update response. Please try again later.", invalid); }
    }

    static Optional<Release> parseRelease(JsonObject object) {
        try {
            if (object.has("draft") && object.get("draft").getAsBoolean()) return Optional.empty();
            String tag = string(object, "tag_name");
            if (!VERSION.matcher(tag).matches()) return Optional.empty();
            String version = tag.replaceFirst("^[vV]", "");
            // Validate numeric prerelease identifiers, which cannot contain leading zeros.
            versionParts(version);
            JsonArray assets = object.getAsJsonArray("assets");
            if (assets == null) return Optional.empty();
            for (JsonElement element : assets) {
                if (!element.isJsonObject()) continue;
                JsonObject asset = element.getAsJsonObject();
                if (!"moondrop.jar".equals(string(asset, "name"))) continue;
                URI download = trustedAsset(string(asset, "browser_download_url"), tag, "moondrop.jar");
                if (download == null) continue;
                String digest = string(asset, "digest"), sha = "";
                if (!digest.isEmpty()) {
                    if (!digest.matches("sha256:[0-9a-fA-F]{64}")) continue;
                    sha = digest.substring(7).toLowerCase(Locale.ROOT);
                }
                String name = string(object, "name");
                return Optional.of(new Release(version, name.isBlank() ? "Moondrop " + version : name,
                    URI.create("https://github.com/Sipper1236/moondrop/releases/tag/" + tag), download, sha));
            }
        } catch (RuntimeException invalid) { /* Ignore malformed or unrelated releases. */ }
        return Optional.empty();
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
    private static URI trustedAsset(String value, String tag, String filename) {
        try {
            URI expected = URI.create(PREFIX + tag + "/" + filename), actual = URI.create(value);
            return expected.equals(actual) ? actual : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    static String checksum(String text) {
        for (String line : text.split("\\R")) {
            Matcher match = Pattern.compile("^([0-9a-fA-F]{64})[ \\t]+\\*?moondrop\\.jar[ \\t]*$").matcher(line);
            if (match.matches()) return match.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    public Path download(Release release, Consumer<String> progress) throws Exception {
        requireTrusted(release);
        try {
            Path staged = directory.resolve("moondrop-" + release.version() + ".jar");
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                if (validCached(staged, release)) {
                    progress.accept("Update already downloaded and verified");
                    return staged;
                }
                Files.deleteIfExists(staged);
            }
            HttpResponse<InputStream> response = client.send(request(release.download(), 120), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) throw new IOException("GitHub could not provide the update (HTTP " + response.statusCode() + "). Try again later.");
                OptionalLong length = response.headers().firstValueAsLong("Content-Length");
                if (length.isPresent() && length.getAsLong() > MAX_JAR) throw new IOException("The update exceeds the 64 MiB download limit.");
                return boundedTime(input, 120, () -> stage(release, input, progress));
            }
        } catch (InterruptedException cancellation) { Thread.currentThread().interrupt(); throw cancellation; }
        catch (IOException e) { throw friendly(e, "Could not download the update. Check your internet connection and try again."); }
    }

    static boolean validCached(Path path, Release release) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_JAR) return false;
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            byte[] buffer = new byte[16384]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Update check cancelled.");
                total += count; if (total > MAX_JAR) return false;
                digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(release.sha256())) return false;
        }
        try { validateJar(path, release.version()); return true; }
        catch (IOException invalid) { return false; }
    }

    /** Package seam for integrity tests; production calls this only after a trusted HTTPS response. */
    Path stage(Release release, InputStream input, Consumer<String> progress) throws IOException {
        requireTrusted(release);
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".moondrop-update-", ".part");
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long total = 0, lastProgress = -1;
            try (OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Update download cancelled.");
                    total += count;
                    if (total > MAX_JAR) throw new IOException("The update exceeds the 64 MiB download limit.");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    long megabytes = total / (1024 * 1024);
                    if (megabytes != lastProgress) { progress.accept("Downloading update · " + megabytes + " MiB"); lastProgress = megabytes; }
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    release.sha256().toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("The update checksum does not match. The download was discarded; please try again.");
            validateJar(temp, release.version());
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Update download cancelled.");
            Path destination = directory.resolve("moondrop-" + release.version() + ".jar");
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally { Files.deleteIfExists(temp); }
    }

    private static void requireTrusted(Release release) throws IOException {
        try {
            versionParts(release.version());
            if (!release.version().matches("[0-9A-Za-z.+-]+") || release.version().startsWith("v") || release.version().startsWith("V")) throw new IllegalArgumentException();
            String path = release.download().getRawPath();
            String tag = path.substring(path.lastIndexOf('/', path.lastIndexOf('/') - 1) + 1, path.lastIndexOf('/'));
            if (!tag.replaceFirst("^[vV]", "").equals(release.version()) || trustedAsset(release.download().toString(), tag, "moondrop.jar") == null
                || !release.sha256().matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IOException("The update metadata is invalid. Check for updates again."); }
    }
    static void validateJar(Path file, String version) throws IOException {
        try (JarFile jar = new JarFile(file.toFile(), true)) {
            if (jar.getManifest() == null) throw new IOException("Missing manifest");
            var attributes = jar.getManifest().getMainAttributes();
            if (!"xyz.shadowmere.desktop.Main".equals(attributes.getValue("Main-Class"))
                || !version.equals(attributes.getValue("Implementation-Version"))
                || jar.getJarEntry("xyz/shadowmere/desktop/Main.class") == null)
                throw new IOException("Unexpected application metadata");
        } catch (IOException invalid) { throw new IOException("The update is not a valid Moondrop application. The download was discarded.", invalid); }
    }

    private HttpRequest request(URI uri, int seconds) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds)).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Moondrop-Updater").GET().build();
    }
    private byte[] fetch(URI uri, int limit, int seconds) throws Exception {
        HttpResponse<InputStream> response = client.send(request(uri, seconds), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException(response.statusCode() == 403 || response.statusCode() == 429
                ? "GitHub is limiting update checks. Please try again later."
                : "GitHub could not provide update information (HTTP " + response.statusCode() + "). Try again later.");
            return boundedTime(input, seconds, () -> {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("The update response is larger than expected.");
                return bytes;
            });
        }
    }
    private static <T> T boundedTime(InputStream stream, int seconds, Callable<T> operation) throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor(work -> { Thread thread = new Thread(work, "update-download"); thread.setDaemon(true); return thread; });
        Future<T> future = reader.submit(operation);
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { throw new IOException("The update request timed out. Please try again."); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new IOException("The update could not be downloaded.", failure.getCause());
        } finally {
            future.cancel(true);
            try { stream.close(); } finally { reader.shutdownNow(); }
        }
    }
    private static IOException friendly(IOException failure, String fallback) {
        if (failure instanceof javax.net.ssl.SSLException || hasTlsCause(failure))
            return new IOException("Java could not verify GitHub’s HTTPS certificate. Update Java and your system certificates, then try again.", failure);
        if (failure instanceof java.net.http.HttpTimeoutException || failure instanceof java.net.ConnectException || failure instanceof java.net.UnknownHostException)
            return new IOException(fallback, failure);
        return failure;
    }
    private static boolean hasTlsCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) return true;
        return false;
    }
    private record VersionParts(List<java.math.BigInteger> core, List<String> pre) { }
    private static VersionParts versionParts(String text) {
        Matcher match = VERSION.matcher(text);
        if (!match.matches()) throw new IllegalArgumentException("Invalid application version");
        List<String> pre = match.group(4) == null ? List.of() : List.of(match.group(4).split("\\."));
        for (String identifier : pre) if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.startsWith("0")) throw new IllegalArgumentException("Invalid prerelease version");
        return new VersionParts(List.of(new java.math.BigInteger(match.group(1)), new java.math.BigInteger(match.group(2)), new java.math.BigInteger(match.group(3))), pre);
    }
    static int compareVersions(String left, String right) {
        VersionParts a = versionParts(left), b = versionParts(right);
        for (int i = 0; i < 3; i++) { int comparison = a.core().get(i).compareTo(b.core().get(i)); if (comparison != 0) return comparison; }
        if (a.pre().isEmpty() || b.pre().isEmpty()) return a.pre().isEmpty() ? (b.pre().isEmpty() ? 0 : 1) : -1;
        for (int i = 0; i < Math.min(a.pre().size(), b.pre().size()); i++) {
            String x = a.pre().get(i), y = b.pre().get(i); boolean xn = x.matches("[0-9]+"), yn = y.matches("[0-9]+");
            int comparison = xn && yn ? new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y)) : xn != yn ? (xn ? -1 : 1) : x.compareTo(y);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.pre().size(), b.pre().size());
    }
}
