package xyz.moondrop.android;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import java.util.Map;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;

public class ServerRepositoryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static Server server(String password){return new Server("one","example.org",443,"aes-256-gcm",password,"US","","",true);}
    @Test public void cachedProfilesAndMeasurementsAreAvailableWithoutNetwork() throws Exception {
        ServerRepository repository=new ServerRepository(temporary.newFolder().toPath().resolve("cache.json").toFile());
        Server server=server("private-password");Measurement result=new Measurement(42,System.currentTimeMillis(),400000,3,3);
        repository.save(List.of(server),Map.of(ServerRepository.key(server),result));
        ServerRepository.Snapshot cached=repository.load();assertEquals(1,cached.servers().size());
        assertEquals("private-password",cached.servers().get(0).password());assertEquals(42,cached.measurements().get(ServerRepository.key(server)).latencyMs);
    }
    @Test public void credentialChangesInvalidateConnectionIdentity(){assertNotEquals(ServerRepository.key(server("first")),ServerRepository.key(server("second")));assertFalse(ServerRepository.key(server("first")).contains("first"));}
    @Test public void unsafeAndMalformedProfilesAreExcluded(){String input="socks5://user:secret@example.org:1080\nss://none:password@example.org:443\nss://2022-blake3-aes-128-gcm:bad@example.org:443\nvless://12345678-1234-1234-1234-123456789abc@example.org:443?security=none\nss://aes-256-gcm:private@example.org:443#%F0%9F%87%BA%F0%9F%87%B8\n";List<Server> servers=ServerRepository.parseFeed(input,"test:");assertEquals(1,servers.size());assertEquals("US",servers.get(0).country());assertEquals("shadowsocks",servers.get(0).protocol());}
    @Test public void verifiedTlsProfilesRemainSupported(){List<Server> servers=ServerRepository.parseFeed("trojan://secret@example.org:443?security=tls&sni=example.org\n","test:");assertEquals(1,servers.size());assertEquals("trojan",servers.get(0).protocol());}
    @Test public void oldMeasurementsRequireRetesting(){Measurement result=new Measurement(42,System.currentTimeMillis()-Measurement.MAX_AGE-1,0,3,3);assertTrue(result.verified);assertFalse(result.fresh());}
    @Test public void reliabilityAndThroughputAffectRanking(){long now=System.currentTimeMillis();assertTrue(new Measurement(100,now,1000000,3,3).score()<new Measurement(20,now,1000,3,3).score());assertTrue(new Measurement(100,now,1000000,3,3).score()<new Measurement(50,now,1000000,1,3).score());}
}
