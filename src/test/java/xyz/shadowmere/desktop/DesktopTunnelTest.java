package xyz.shadowmere.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class DesktopTunnelTest {
    @TempDir Path data;
    @Test void allPrivilegedBackendsAreBundled()throws Exception {
        for(String name:List.of("linux-tunnel-helper.py","macos-tunnel-helper.py","windows-tunnel-helper.ps1"))try(var input=getClass().getResourceAsStream("/META-INF/moondrop/scripts/"+name)) {
            assertNotNull(input,"Missing platform helper: "+name);assertTrue(input.readAllBytes().length>5000);
        }
    }
    @Test void rebootedOrReusedWindowsPidNeverCountsAsActive() {
        var process=ProcessHandle.current();JsonObject status=new JsonObject();status.addProperty("pid",process.pid());
        status.addProperty("started",process.info().startInstant().orElseThrow().toString());
        assertTrue(NativeDesktopTunnel.liveWindowsEngine(status));
        status.addProperty("started","2000-01-01T00:00:00Z");assertFalse(NativeDesktopTunnel.liveWindowsEngine(status));
        status.addProperty("pid",Long.MAX_VALUE);assertFalse(NativeDesktopTunnel.liveWindowsEngine(status));
        assertFalse(NativeDesktopTunnel.liveWindowsEngine(new JsonObject()));
    }
    @Test void helperInstallExecutesVerifiedBytesEvenIfStagedFileChanges()throws Exception {
        String shell=System.getProperty("tunnel.test.powershell");assumeTrue(shell!=null,"Supply PowerShell to exercise the real parser/runtime");
        Path source=data.resolve("helper with 'quotes'.ps1"),result=data.resolve("result.txt");
        byte[] original="param($Action,$VerifiedSource) [IO.File]::WriteAllText($global:Result,'verified')".getBytes(StandardCharsets.UTF_8);Files.write(source,original);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        String command=NativeDesktopTunnel.verifiedWindowsScript(source,digest);
        // Replace disk content after the digest check, before invocation. The verified snapshot must win.
        command=command.replace(";& ([scriptblock]", ";[IO.File]::WriteAllText("+NativeDesktopTunnel.psQuote(source.toString())+","+NativeDesktopTunnel.psQuote("throw 'disk replacement executed'")+");& ([scriptblock]");
        command="$global:Result="+NativeDesktopTunnel.psQuote(result.toString())+";"+command+" -Action Install -VerifiedSource $bytes";
        Process child=new ProcessBuilder(shell,"-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        assertTrue(child.waitFor(20,TimeUnit.SECONDS));String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,child.exitValue(),output);
        assertEquals("verified",Files.readString(result));
    }
    @Test void helpersCannotBeSelectedWithAnUnverifiedDigest() {
        assertThrows(IllegalArgumentException.class,()->NativeDesktopTunnel.verifiedWindowsScript(data.resolve("helper.ps1"),"invalid;exec"));
    }
}
