package xyz.shadowmere.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Independent publisher authentication. Never trusts keys supplied by a release. */
public final class ReleaseSignatures {
    public static final String MANIFEST = "release-manifest.txt", SIGNATURE = "release-manifest.sig";
    public static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAsA36dF1KiRLz+mg66+LEivITZ/lZ8X3HXPHqJ9+ShCg=";
    private ReleaseSignatures() { }
    static PublicKey trustedKey() {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))); }
        catch (GeneralSecurityException invalid) { throw new IllegalStateException(invalid); }
    }
    public static String verify(byte[] manifest, byte[] signature, String version, String artifact) throws IOException {
        return verify(manifest, signature, version, artifact, trustedKey());
    }
    static String verify(byte[] manifest, byte[] signature, String version, String artifact, PublicKey key) throws IOException {
        if (manifest.length > 65536 || signature.length != 64) throw new IOException("Invalid signed release manifest.");
        try {
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(manifest);
            if (!verifier.verify(signature)) throw new IOException("The release signature is invalid. No update was installed.");
        } catch (GeneralSecurityException invalid) { throw new IOException("Could not authenticate this release.", invalid); }
        String text = new String(manifest, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || !lines[0].equals("moondrop-release-v1") || !lines[1].equals("version " + version) || !lines[lines.length-1].isEmpty())
            throw new IOException("The signed release version does not match.");
        String result = null; Set<String> names = new HashSet<>();
        for (int i = 2; i < lines.length - 1; i++) {
            if (!lines[i].matches("[0-9a-f]{64}  [A-Za-z0-9][A-Za-z0-9._-]*")) throw new IOException("Invalid signed artifact entry.");
            String name = lines[i].substring(66);
            if (!names.add(name)) throw new IOException("Duplicate signed artifact entry.");
            if (name.equals(artifact)) result = lines[i].substring(0,64);
        }
        if (result == null) throw new IOException("This artifact is absent from the signed release.");
        return result;
    }
    static void verifyStaged(Path jar, String version, String expected) throws IOException { verifyStaged(jar,version,expected,trustedKey()); }
    static void verifyStaged(Path jar, String version, String expected, PublicKey key) throws IOException {
        String digest = verify(Files.readAllBytes(jar.resolveSibling(jar.getFileName()+".manifest")),
            Files.readAllBytes(jar.resolveSibling(jar.getFileName()+".sig")), version, "moondrop.jar",key);
        if (!digest.equals(expected)) throw new IOException("The signed update digest changed.");
    }
}
