package xyz.shadowmere.desktop;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecureServerCacheTest {
    @TempDir Path directory;
    private static class Vault implements SecretStore {
        byte[] key;boolean locked;int writes;
        public byte[] load()throws IOException{if(locked)throw new IOException("Fixture vault locked");return key==null?null:key.clone();}
        public void save(byte[] value)throws IOException{if(locked)throw new IOException("Fixture vault locked");key=value.clone();writes++;}
        public void erase()throws IOException{if(locked)throw new IOException("Fixture vault locked");key=null;}
    }
    private Server profile() {
        return ProxyLinks.parse("collector:fixture","trojan://ultra-secret-token@1.1.1.1:443?sni=example.org&type=ws&path=%2Fprivate-path","CA","A human name","",true);
    }
    private Path metadata(){return directory.resolve("server-catalog.json");}
    private Path encrypted()throws IOException {
        var root=JsonParser.parseString(Files.readString(metadata())).getAsJsonObject();
        return directory.resolve(root.get("encryptedFile").getAsString());
    }
    @Test void v2SeparatesNonsecretMetadataAndEncryptsAllReusableCredentials()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);
        cache.save(List.of(profile()),true);
        assertEquals(List.of(profile()),new ServerCache(directory,vault).load());
        String json=Files.readString(metadata());
        assertTrue(json.contains("\"version\":2"));assertTrue(json.contains("1.1.1.1"));
        for(String secret:List.of("ultra-secret-token","private-path","password","profileJson","example.org","A human name")) {
            assertFalse(json.contains(secret),secret);
            assertFalse(new String(Files.readAllBytes(encrypted()),java.nio.charset.StandardCharsets.ISO_8859_1).contains(secret),secret);
        }
        assertEquals(32,vault.key.length);assertEquals(1,vault.writes);
        assertEquals(Set.of("host","port","protocol","method","country","secretRef"),JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("servers").get(0).getAsJsonObject().keySet());
    }
    @Test void tamperingOrWrongKeyFailsAuthenticationInsteadOfLoadingProfiles()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()),true);
        Path file=encrypted();byte[] original=Files.readAllBytes(file),altered=original.clone();altered[altered.length-1]^=1;Files.write(file,altered);
        assertThrows(IOException.class,cache::load);
        Files.write(file,original);vault.key[0]^=1;
        assertThrows(IOException.class,cache::load);
    }
    @Test void lockedOrMissingVaultNeverDowngradesEncryptedCacheToPlaintext()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()),true);
        byte[] original=Files.readAllBytes(metadata());vault.locked=true;
        assertThrows(IOException.class,cache::load);assertThrows(IOException.class,()->cache.save(List.of(profile()),true));
        assertArrayEquals(original,Files.readAllBytes(metadata()));
        vault.locked=false;vault.key=null;assertThrows(IOException.class,cache::load);
        assertThrows(IOException.class,()->cache.save(List.of(profile()),true));
        assertArrayEquals(original,Files.readAllBytes(metadata()));
    }
    @Test void failedOptInLeavesPreviousPlaintextSnapshotUntouched()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()));
        byte[] original=Files.readAllBytes(metadata());vault.locked=true;
        assertThrows(IOException.class,()->cache.save(List.of(profile()),true));
        assertArrayEquals(original,Files.readAllBytes(metadata()));
        assertEquals(List.of(profile()),cache.load());
        try(var paths=Files.list(directory)){assertEquals(1,paths.count());}
    }
    @Test void migrationsAndErasureLeaveUnrelatedFilesAlone()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()));
        Files.writeString(directory.resolve("unrelated.txt"),"keep");
        cache.save(List.of(profile()),true);Path first=encrypted();
        cache.save(List.of(profile()),true);assertFalse(Files.exists(first));assertEquals(1,vault.writes);
        cache.save(List.of(profile()),false);assertEquals(List.of(profile()),cache.load());
        try(var paths=Files.list(directory)){assertEquals(2,paths.count());}
        cache.save(List.of(profile()),true);cache.erase();assertNull(vault.key);assertTrue(cache.load().isEmpty());
        assertEquals("keep",Files.readString(directory.resolve("unrelated.txt")));
        try(var paths=Files.list(directory)){assertEquals(1,paths.count());}
    }
    @Test void invalidSecretFileReferenceCannotReadOutsideDataDirectory()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()),true);
        JsonObject root=JsonParser.parseString(Files.readString(metadata())).getAsJsonObject();
        root.addProperty("encryptedFile","../outside.bin");Files.writeString(metadata(),root.toString());
        assertThrows(IOException.class,cache::load);
    }
    @Test void metadataHostTamperingIsRejected()throws Exception {
        Vault vault=new Vault();ServerCache cache=new ServerCache(directory,vault);cache.save(List.of(profile()),true);
        JsonObject root=JsonParser.parseString(Files.readString(metadata())).getAsJsonObject();
        root.getAsJsonArray("servers").get(0).getAsJsonObject().addProperty("host","192.168.1.1");
        Files.writeString(metadata(),root.toString());assertThrows(IOException.class,cache::load);
    }
}
