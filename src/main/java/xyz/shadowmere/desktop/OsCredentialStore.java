package xyz.shadowmere.desktop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native credential tools receive secret input through stdin, never process arguments.
 * Windows stores only a CurrentUser-DPAPI-encrypted key blob in the data directory. */
public final class OsCredentialStore implements SecretStore {
    private final String os, scope;
    private final Path blob;
    public OsCredentialStore(Path directory) {
        os=System.getProperty("os.name").toLowerCase(Locale.ROOT);
        blob=directory.resolve("server-catalog-key.dpapi");
        try {
            scope=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(directory.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) {throw new AssertionError(impossible);}
    }
    @Override public byte[] load() throws IOException {
        Result result;
        if(os.contains("win")) {
            if(!Files.exists(blob))return null;
            if(Files.size(blob)>16384)throw unavailable();
            result=powershell(false,Files.readString(blob));
        } else if(os.contains("mac")) {
            result=run(List.of("security","find-generic-password","-s","xyz.moondrop.catalog","-a",scope,"-w"),"");
            if(result.code==44)return null;
        } else {
            result=run(List.of("secret-tool","lookup","application","xyz.moondrop","catalog",scope),"");
            if(result.code==1 && result.output.isBlank())return null;
        }
        if(result.code!=0)throw unavailable();
        try {
            byte[] key=Base64.getDecoder().decode(result.output.strip());
            if(key.length!=32)throw unavailable();
            return key;
        } catch(IllegalArgumentException invalid) {throw unavailable();}
    }
    @Override public void save(byte[] key) throws IOException {
        if(key.length!=32)throw new IOException("Invalid catalog encryption key length.");
        String encoded=Base64.getEncoder().encodeToString(key);
        Result result;
        if(os.contains("win")) {
            result=powershell(true,encoded);
            if(result.code!=0)throw unavailable();
            Files.createDirectories(blob.getParent());
            Path temp=privateTemp(blob.getParent(),".catalog-key-");
            try {
                Files.writeString(temp,result.output.strip());
                Files.move(temp,blob,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(temp);}
            return;
        } else if(os.contains("mac")) {
            // security's interactive command interpreter reads the password from stdin.
            // Its CLI otherwise requires -w in argv, which would expose the key in process listings.
            result=run(List.of("security","-i"),"add-generic-password -U -s xyz.moondrop.catalog -a "+scope+" -w "+encoded+"\nquit\n");
        } else {
            result=run(List.of("secret-tool","store","--label=Moondrop server catalog","application","xyz.moondrop","catalog",scope),encoded);
        }
        if(result.code!=0)throw unavailable();
        // Some interactive native tools can exit successfully after a command error.
        byte[] stored=load();
        if(stored==null || !MessageDigest.isEqual(stored,key))throw unavailable();
        Arrays.fill(stored,(byte)0);
    }
    @Override public void erase() throws IOException {
        if(os.contains("win")) {Files.deleteIfExists(blob);return;}
        Result result=os.contains("mac")
                ?run(List.of("security","delete-generic-password","-s","xyz.moondrop.catalog","-a",scope),"")
                :run(List.of("secret-tool","clear","application","xyz.moondrop","catalog",scope),"");
        if(result.code!=0 && !(os.contains("mac")&&result.code==44) && !( !os.contains("mac")&&result.code==1))throw unavailable();
    }
    private Result powershell(boolean protect,String input) throws IOException {
        String operation=protect?"Protect":"Unprotect";
        String script="$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                +"$b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); "
                +"$r=[Security.Cryptography.ProtectedData]::"+operation+"($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                +"[Console]::Out.Write([Convert]::ToBase64String($r))";
        String command=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return run(List.of("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",command),input);
    }
    private record Result(int code,String output) { }
    private static Result run(List<String> command,String input) throws IOException {
        Process process;
        try {process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();}
        catch(IOException unavailable) {throw unavailable();}
        try {
            try(OutputStream stdin=process.getOutputStream()) {stdin.write(input.getBytes(StandardCharsets.UTF_8));}
            // Credential responses are tiny. Drain concurrently to keep native UI/tool failures bounded.
            var response=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {return process.getInputStream().readNBytes(16385);}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}
            });
            if(!process.waitFor(20,TimeUnit.SECONDS)) {process.destroyForcibly();throw unavailable();}
            byte[] bytes=response.get(2,TimeUnit.SECONDS);
            if(bytes.length>16384)throw unavailable();
            return new Result(process.exitValue(),new String(bytes,StandardCharsets.UTF_8));
        } catch(InterruptedException cancelled) {Thread.currentThread().interrupt();throw new IOException("Credential storage request cancelled.");}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure) {throw unavailable();}
        finally {if(process.isAlive())process.destroyForcibly();}
    }
    static Path privateTemp(Path parent,String prefix) throws IOException {
        try {return Files.createTempFile(parent,prefix,".tmp",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));}
        catch(UnsupportedOperationException unsupported) {return Files.createTempFile(parent,prefix,".tmp");}
    }
    private static IOException unavailable() {return new IOException("The operating system credential store is unavailable or locked. Unlock it and try again; profiles were not saved in plaintext.");}
}
