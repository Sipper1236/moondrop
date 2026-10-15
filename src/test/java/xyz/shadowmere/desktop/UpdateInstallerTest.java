package xyz.shadowmere.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class UpdateInstallerTest {
    @TempDir Path directory;

    private Path jar(String name,String version) throws Exception {
        Path jar=directory.resolve(name);Manifest manifest=new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Main-Class","xyz.shadowmere.desktop.Main");
        manifest.getMainAttributes().putValue("Implementation-Version",version);
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar),manifest)) {
            out.putNextEntry(new JarEntry("xyz/shadowmere/desktop/Main.class"));out.write(new byte[]{1,2,3});out.closeEntry();
        }
        return jar;
    }
    private String hash(Path file) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private String awaitContent(Path file,String expected,long deadline) throws Exception {
        String content="<not written>";
        while(System.nanoTime()<deadline) {
            try {content=Files.readString(file);}catch(NoSuchFileException notYetCreated) { }
            if(expected.equals(content))return content;
            Thread.sleep(50);
        }
        return content;
    }
    private UpdateInstaller.Plan plan(Path old,Path staged,String version) throws Exception {
        return new UpdateInstaller.Plan(1,Instant.EPOCH.toString(),old,staged,directory.resolve("moondrop.jar.previous"),hash(staged),version,
                Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java"),directory);
    }
    @Test void installsVerifiedJarAndKeepsRollbackCopy() throws Exception {
        Path old=jar("moondrop.jar","0.3.0"),next=jar("next.jar","0.3.1");byte[] original=Files.readAllBytes(old);
        var plan=plan(old,next,"0.3.1");UpdateInstaller.install(plan);
        assertArrayEquals(Files.readAllBytes(next),Files.readAllBytes(old));assertArrayEquals(original,Files.readAllBytes(plan.backup()));
        UpdateInstaller.restore(plan);assertArrayEquals(original,Files.readAllBytes(old));
    }
    @Test void tamperedStageNeverChangesInstalledJar() throws Exception {
        Path old=jar("moondrop.jar","0.3.0"),next=jar("next.jar","0.3.1");var plan=plan(old,next,"0.3.1");byte[] original=Files.readAllBytes(old);
        Files.writeString(next,"tampered");assertThrows(IOException.class,()->UpdateInstaller.install(plan));
        assertArrayEquals(original,Files.readAllBytes(old));assertFalse(Files.exists(plan.backup()));
    }
    @Test void wrongVersionNeverChangesInstalledJar() throws Exception {
        Path old=jar("moondrop.jar","0.3.0"),next=jar("next.jar","0.3.1");byte[] original=Files.readAllBytes(old);
        assertThrows(IOException.class,()->UpdateInstaller.install(plan(old,next,"0.9.0")));assertArrayEquals(original,Files.readAllBytes(old));
    }
    @Test void refusesToReplaceJarWhileSameAppProcessIsAlive() {
        Instant started=ProcessHandle.current().info().startInstant().orElseThrow();
        assertThrows(IOException.class,()->UpdateInstaller.waitForExit(ProcessHandle.current().pid(),started,0));
    }
    @Test void planRoundTripHandlesSpacesAndUnicode() throws Exception {
        Path folder=directory.resolve("my app 雪");Files.createDirectories(folder);
        Path old=jar("moondrop.jar","0.3.0"),next=jar("next.jar","0.3.1");var expected=plan(old,next,"0.3.1");
        Path file=folder.resolve("update.properties");UpdateInstaller.writePlan(file,expected);assertEquals(expected,UpdateInstaller.readPlan(file));
    }
    @Test void standaloneHelperInstallsAndRelaunchesWithoutApplicationClasspath() throws Exception {
        Path old=jar("moondrop.jar","0.3.0"),next=directory.resolve("new.jar");
        Path source=directory.resolve("fixture/xyz/shadowmere/desktop/Main.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"""
            package xyz.shadowmere.desktop;
            public class Main {
              public static void main(String[] args) throws Exception {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("shadowmere.dataDir"),"restarted"),"0.3.1");
              }
            }
            """);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,source.toString()));
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Main-Class","xyz.shadowmere.desktop.Main");manifest.getMainAttributes().putValue("Implementation-Version","0.3.1");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(next),manifest)) {
            out.putNextEntry(new JarEntry("xyz/shadowmere/desktop/Main.class"));Files.copy(source.resolveSibling("Main.class"),out);out.closeEntry();
        }
        Path updates=directory.resolve("updates");Files.createDirectories(updates);Path helper=updates.resolve("installer-helper.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(helper))) {
            for(String name:new String[]{"UpdateInstaller.class","UpdateInstaller$Plan.class"}) {
                String resource="xyz/shadowmere/desktop/"+name;out.putNextEntry(new JarEntry(resource));
                try(InputStream input=UpdateInstaller.class.getClassLoader().getResourceAsStream(resource)){assertNotNull(input);input.transferTo(out);}out.closeEntry();
            }
        }
        var p=plan(old,next,"0.3.1");
        // A deliberately mismatched start instant represents an already exited parent/PID reuse.
        p=new UpdateInstaller.Plan(ProcessHandle.current().pid(),Instant.EPOCH.toString(),p.installed(),p.staged(),p.backup(),p.sha256(),p.version(),p.java(),p.data());
        Path file=updates.resolve("install.properties");UpdateInstaller.writePlan(file,p);Process installer=UpdateInstaller.start(file);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        assertEquals("0.3.1",awaitContent(directory.resolve("restarted"),"0.3.1",deadline),
                ()->{try{return Files.readString(updates.resolve("install.log"));}catch(Exception e){return e.toString();}});
        while(Files.exists(file)&&System.nanoTime()<deadline)Thread.sleep(100);
        assertFalse(Files.exists(file));assertTrue(Files.isRegularFile(p.backup()));
        assertTrue(installer.waitFor(5,TimeUnit.SECONDS));assertEquals(0,installer.exitValue());

        // A valid manifest/checksum cannot guarantee that a JVM can load the new Main class.
        // Verify that an actual failed restart restores and launches the prior runnable JAR.
        Files.delete(directory.resolve("restarted"));byte[] working=Files.readAllBytes(old);
        Path bad=jar("unloadable.jar","0.3.2");var rollback=plan(old,bad,"0.3.2");
        rollback=new UpdateInstaller.Plan(ProcessHandle.current().pid(),Instant.EPOCH.toString(),rollback.installed(),rollback.staged(),rollback.backup(),rollback.sha256(),rollback.version(),rollback.java(),rollback.data());
        Path retry=updates.resolve("rollback.properties");UpdateInstaller.writePlan(retry,rollback);Process failed=UpdateInstaller.start(retry);
        assertTrue(failed.waitFor(15,TimeUnit.SECONDS));assertEquals(1,failed.exitValue());
        assertArrayEquals(working,Files.readAllBytes(old));
        assertEquals("0.3.1",awaitContent(directory.resolve("restarted"),"0.3.1",System.nanoTime()+TimeUnit.SECONDS.toNanos(15)));
    }
}
