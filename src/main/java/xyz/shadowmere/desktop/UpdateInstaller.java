package xyz.shadowmere.desktop;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/** A small, dependency-free helper can replace a JAR after Windows releases its file lock. */
public final class UpdateInstaller {
    record Plan(long pid,String started,Path installed,Path staged,Path backup,String sha256,String version,Path java,Path data) { }
    private UpdateInstaller() { }

    public static Path prepare(Path staged,String sha256,String version,Path dataDir) throws Exception {
        Path installed=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if(!installed.toString().endsWith(".jar")||!Files.isRegularFile(installed))
            throw new IOException("Automatic installation works from the downloaded JAR. Download Moondrop.zip to update a source build.");
        if(!Files.isWritable(installed)||!Files.isWritable(installed.getParent()))
            throw new IOException("This app folder is read-only. Move Moondrop to a folder you own, then try again.");
        Path updates=dataDir.toAbsolutePath().resolve("updates");Files.createDirectories(updates);
        Path helper=updates.resolve("installer-helper.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(helper))) {
            for(String name:List.of("UpdateInstaller.class","UpdateInstaller$Plan.class")) {
                String resource="xyz/shadowmere/desktop/"+name;
                try(InputStream in=UpdateInstaller.class.getClassLoader().getResourceAsStream(resource)) {
                    if(in==null)throw new IOException("The update helper is missing from this build.");
                    out.putNextEntry(new JarEntry(resource));in.transferTo(out);out.closeEntry();
                }
            }
        }
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        String started=ProcessHandle.current().info().startInstant().orElseThrow().toString();
        Plan plan=new Plan(ProcessHandle.current().pid(),started,installed,staged.toAbsolutePath().normalize(),
                installed.resolveSibling(installed.getFileName()+".previous"),sha256,version,java,dataDir.toAbsolutePath());
        Path file=Files.createTempFile(updates,"install-", ".properties");writePlan(file,plan);return file;
    }

    public static Process start(Path planFile) throws Exception {
        Plan plan=readPlan(planFile);
        Path helper=planFile.getParent().resolve("installer-helper.jar");
        return new ProcessBuilder(plan.java().toString(),"-cp",helper.toString(),UpdateInstaller.class.getName(),planFile.toString())
                .redirectErrorStream(true).redirectOutput(planFile.getParent().resolve("install.log").toFile()).start();
    }

    public static void main(String[] args) {
        if(args.length!=1){System.err.println("An update plan is required.");System.exit(1);}
        try {
            Path file=Path.of(args[0]);Plan plan=readPlan(file);
            waitForExit(plan.pid(),Instant.parse(plan.started()),60);
            try {install(plan);}
            catch(Exception failure) {launch(plan);throw failure;}
            try {launch(plan);}
            catch(IOException failure) {restore(plan);launch(plan);throw new IOException("The new app could not launch; the previous version was restored.",failure);}
            Files.deleteIfExists(plan.staged());Files.deleteIfExists(file);
        } catch(Exception failure) {failure.printStackTrace(System.err);System.exit(1);}
    }

    static void waitForExit(long pid,Instant started,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(true) {
            Optional<ProcessHandle> found=ProcessHandle.of(pid);
            if(found.isEmpty()||!found.get().isAlive())return;
            Optional<Instant> actual=found.get().info().startInstant();
            if(actual.isEmpty())throw new IOException("Cannot safely confirm the previous app has closed. No application files were changed.");
            if(!actual.get().equals(started))return;
            if(System.nanoTime()>=deadline)throw new IOException("Moondrop did not close. No application files were changed.");
            Thread.sleep(100);
        }
    }

    static void install(Plan plan) throws Exception {
        verify(plan.staged(),plan.sha256(),plan.version());
        Path replacement=Files.createTempFile(plan.installed().getParent(),".moondrop-update-", ".jar");
        try {
            Files.copy(plan.staged(),replacement,StandardCopyOption.REPLACE_EXISTING);
            verify(replacement,plan.sha256(),plan.version());
            Files.copy(plan.installed(),plan.backup(),StandardCopyOption.REPLACE_EXISTING);
            Files.move(replacement,plan.installed(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(replacement);}
    }

    static void verify(Path jar,String expected,String version) throws Exception {
        if(expected==null||!expected.matches("[0-9a-fA-F]{64}"))throw new IOException("Invalid update checksum.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(jar)) {byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        if(!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected))throw new IOException("The update changed before installation. The existing app was kept.");
        try(JarFile archive=new JarFile(jar.toFile())) {
            Manifest manifest=archive.getManifest();
            if(manifest==null||!"xyz.shadowmere.desktop.Main".equals(manifest.getMainAttributes().getValue("Main-Class"))
                    ||!version.equals(manifest.getMainAttributes().getValue("Implementation-Version"))
                    ||archive.getEntry("xyz/shadowmere/desktop/Main.class")==null)
                throw new IOException("The downloaded file is not the expected Moondrop version.");
        }
    }

    static void restore(Plan plan) throws IOException {
        if(Files.isRegularFile(plan.backup()))Files.copy(plan.backup(),plan.installed(),StandardCopyOption.REPLACE_EXISTING);
    }

    private static void launch(Plan plan) throws IOException {
        Process child=new ProcessBuilder(plan.java().toString(),"-Dshadowmere.dataDir="+plan.data(),"-jar",plan.installed().toString())
                .directory(plan.installed().getParent().toFile()).redirectErrorStream(true)
                .redirectOutput(plan.data().resolve("updates/restart.log").toFile()).start();
        try {if(child.waitFor(1500,TimeUnit.MILLISECONDS)&&child.exitValue()!=0)throw new IOException("Moondrop exited while restarting. See updates/restart.log.");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Restart was interrupted.",interrupted);}
    }

    static void writePlan(Path file,Plan plan) throws IOException {
        Properties p=new Properties();p.setProperty("pid",Long.toString(plan.pid()));p.setProperty("started",plan.started());
        p.setProperty("installed",plan.installed().toString());p.setProperty("staged",plan.staged().toString());
        p.setProperty("backup",plan.backup().toString());p.setProperty("sha256",plan.sha256());p.setProperty("version",plan.version());
        p.setProperty("java",plan.java().toString());p.setProperty("data",plan.data().toString());
        try(Writer out=Files.newBufferedWriter(file)){p.store(out,"Moondrop verified update");}
    }

    static Plan readPlan(Path file) throws IOException {
        Properties p=new Properties();try(Reader in=Files.newBufferedReader(file)){p.load(in);}
        try {return new Plan(Long.parseLong(p.getProperty("pid")),p.getProperty("started"),Path.of(p.getProperty("installed")),
                Path.of(p.getProperty("staged")),Path.of(p.getProperty("backup")),p.getProperty("sha256"),p.getProperty("version"),
                Path.of(p.getProperty("java")),Path.of(p.getProperty("data")));}
        catch(RuntimeException invalid){throw new IOException("The update plan is incomplete.",invalid);}
    }
}
