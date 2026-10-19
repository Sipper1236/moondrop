package xyz.shadowmere.desktop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseSignaturesTest {
 @TempDir Path directory;
 @Test void bindsPublisherVersionArtifactAndDigest() throws Exception {
  byte[] m=SigningFixtures.manifest("1.2.3","a".repeat(64)),s=SigningFixtures.sign(m);
  assertEquals("a".repeat(64),ReleaseSignatures.verify(m,s,"1.2.3","moondrop.jar",SigningFixtures.KEY.getPublic()));
  assertThrows(IOException.class,()->ReleaseSignatures.verify(m,s,"1.2.4","moondrop.jar",SigningFixtures.KEY.getPublic()));
  assertThrows(IOException.class,()->ReleaseSignatures.verify(m,s,"1.2.3","evil.jar",SigningFixtures.KEY.getPublic()));
  m[m.length-2]^=1;
  assertThrows(IOException.class,()->ReleaseSignatures.verify(m,s,"1.2.3","moondrop.jar",SigningFixtures.KEY.getPublic()));
 }
 @Test void checksumAndJarReplacementCannotStageWithoutPublisherSignature() throws Exception {
  var r=new AppUpdater.Release("1.2.3","",URI.create("https://github.com/Sipper1236/moondrop/releases/tag/v1.2.3"),URI.create("https://github.com/Sipper1236/moondrop/releases/download/v1.2.3/moondrop.jar"),"a".repeat(64));
  assertThrows(IOException.class,()->new AppUpdater(directory).stage(r,new ByteArrayInputStream(new byte[0]),x->{}));
  var attacker=SigningFixtures.release(r);
  assertThrows(IOException.class,()->new AppUpdater(directory).stage(attacker,new ByteArrayInputStream(new byte[0]),x->{}));
  assertFalse(Files.exists(directory.resolve("updates")));
 }
 @Test void malformedDuplicateAndUnsignedArtifactListsAreRejected() throws Exception {
  for(String text:List.of("moondrop-release-v1\nversion 1.2.3\n"+"a".repeat(64)+"  ../moondrop.jar\n",new String(SigningFixtures.manifest("1.2.3","a".repeat(64)))+"b".repeat(64)+"  moondrop.jar\n")) {
   byte[] m=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   assertThrows(IOException.class,()->ReleaseSignatures.verify(m,SigningFixtures.sign(m),"1.2.3","moondrop.jar",SigningFixtures.KEY.getPublic()));
  }
 }
}
