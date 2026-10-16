package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {
    @TempDir Path directory;

    @Test void fallbackOnlyRetriesRecordedServerFailuresAndPreservesCancellation() throws Exception {
        var constructor=Main.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        Main app=constructor.newInstance(directory);
        var server=new Server("one","127.0.0.1",443,"aes-256-gcm","password","","","",true);
        var method=Main.class.getDeclaredMethod("retryableServerFailure",Server.class,Exception.class);method.setAccessible(true);
        assertEquals(false,method.invoke(app,server,new IOException("connection failure")));
        Field field=Main.class.getDeclaredField("performance");field.setAccessible(true);
        ((PerformanceResults)field.get(app)).record(server,ServerPerformance.failed());
        assertEquals(false,method.invoke(app,server,new IOException("port is occupied")));
        assertEquals(true,method.invoke(app,server,new Main.ServerConnectionFailure(new IOException("connection failure"))));
        assertEquals(false,method.invoke(app,server,new InterruptedException()));
        Thread.currentThread().interrupt();
        try {assertEquals(false,method.invoke(app,server,new Main.ServerConnectionFailure(new IOException("connection failure"))));}
        finally {Thread.interrupted();}
    }

    @Test void copiedDiagnosticsExcludeCredentialsAddressesAndRawErrors() throws Exception {
        var constructor=Main.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        Main app=constructor.newInstance(directory);
        var server=new Server("private-label","secret.example",443,"aes-256-gcm","private-password","","","",true);
        Field servers=Main.class.getDeclaredField("servers");servers.setAccessible(true);servers.set(app,List.of(server));
        String text=app.diagnostics();
        assertTrue(text.contains("Servers: 1"));
        assertTrue(text.contains("Enabled servers: 1"));
        assertTrue(text.contains("Java:"));
        for(String secret:List.of("secret.example","private-password","private-label",directory.toString()))assertFalse(text.contains(secret));
        assertEquals("TLS certificate or handshake",Main.diagnosticErrorCategory(new IOException("private-password",new SSLHandshakeException("secret.example"))));
        assertEquals("Connection refused or unreachable",Main.diagnosticErrorCategory(new java.net.ConnectException("secret.example")));
        assertEquals("Input/output failure",Main.diagnosticErrorCategory(new IOException("private-password")));
    }
}
