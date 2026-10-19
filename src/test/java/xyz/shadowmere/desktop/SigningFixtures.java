package xyz.shadowmere.desktop;
import java.security.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class SigningFixtures {
    static final KeyPair KEY;
    static { try { KEY=KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    static byte[] manifest(String version,String digest) { return ("moondrop-release-v1\nversion "+version+"\n"+digest+"  moondrop.jar\n").getBytes(StandardCharsets.UTF_8); }
    static byte[] sign(byte[] bytes) throws Exception { Signature s=Signature.getInstance("Ed25519");s.initSign(KEY.getPrivate());s.update(bytes);return s.sign(); }
    static void sidecars(Path jar,String version,String hash) throws Exception { byte[] m=manifest(version,hash);Files.write(jar.resolveSibling(jar.getFileName()+".manifest"),m);Files.write(jar.resolveSibling(jar.getFileName()+".sig"),sign(m)); }
    static AppUpdater.Release release(AppUpdater.Release r) throws Exception { byte[] m=manifest(r.version(),r.sha256());return new AppUpdater.Release(r.version(),r.name(),r.page(),r.download(),r.sha256(),Base64.getEncoder().encodeToString(m),Base64.getEncoder().encodeToString(sign(m))); }
}
