package xyz.shadowmere.desktop;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;

/** Private catalog snapshot, including the credentials required to reconnect offline. */
public final class ServerCache {
    private static final int MAX_BYTES = 40 * 1024 * 1024, MAX_ENTRIES = 100_000;
    private static final Gson GSON = new Gson();
    private final Path file;
    private final SecretStore secrets;
    private boolean legacyPrivateProfilesDropped;

    public ServerCache(Path dataDirectory) { this(dataDirectory, new OsCredentialStore(dataDirectory)); }
    ServerCache(Path dataDirectory, SecretStore secrets) { file = dataDirectory.resolve("server-catalog.json"); this.secrets=secrets; }

    /** Missing preferences must not silently downgrade an encrypted catalog. */
    public static boolean hasEncryptedSnapshot(Path data) {
        Path metadata=data.resolve("server-catalog.json");
        try {
            if(Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)&&Files.size(metadata)<=MAX_BYTES) {
                JsonObject root=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
                if(root.has("version")&&root.get("version").getAsInt()==2)return true;
            }
        }catch(IOException|RuntimeException ignored) { }
        try(var files=Files.list(data)){return files.anyMatch(path->path.getFileName().toString().matches("server-catalog-secrets-[a-f0-9]{32}\\.bin"));}
        catch(IOException ignored){return false;}
    }

    public synchronized List<Server> load() throws IOException {
        legacyPrivateProfilesDropped=false;
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return List.of();
        makePrivate(file);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return List.of();
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() == 2) return loadEncrypted(root);
            if (root.get("version").getAsInt() != 1) return List.of();
            JsonArray entries = root.getAsJsonArray("servers");
            if (entries.size() > MAX_ENTRIES) return List.of();
            List<Server> result = new ArrayList<>(entries.size());
            for (JsonElement entry : entries) {
                try {
                    Server server = GSON.fromJson(entry, Server.class);
                    // Version 1 predates source authentication. A feed could have
                    // supplied a manual: ID and gained the private-endpoint
                    // exemption, so legacy cache entries must pass feed policy.
                    if(PublicEndpoints.manual(server)) server=asLegacyRemote(server);
                    if (safe(server)) result.add(server);
                    else {
                        Server original=GSON.fromJson(entry,Server.class);
                        if(PublicEndpoints.manual(original)&&isPrivateLiteral(original))legacyPrivateProfilesDropped=true;
                    }
                } catch (RuntimeException invalidEntry) { /* Retain valid neighboring profiles. */ }
            }
            return List.copyOf(result);
        } catch (RuntimeException invalidDocument) {
            if(hasEncryptedSnapshot(file.getParent()))throw new IOException("The encrypted server catalog is damaged. Unlock or explicitly erase it before replacing saved profiles.");
            return List.of();
        }
    }

    public synchronized boolean legacyPrivateProfilesDropped(){return legacyPrivateProfilesDropped;}

    private static Server asLegacyRemote(Server server){
        return new Server("legacy-remote:"+server.id(),server.host(),server.port(),server.method(),server.password(),server.country(),server.location(),server.lastChecked(),server.active(),server.protocol(),server.profileJson());
    }
    private static boolean isPrivateLiteral(Server server){
        String host=server.host();
        if(host==null||!(host.contains(":")||host.matches("[0-9.]+")))return false;
        try{return !PublicEndpoints.isPublic(java.net.InetAddress.getByName(host.replace("[","").replace("]","")));}
        catch(Exception invalid){return true;}
    }

    public synchronized void save(List<Server> servers) throws IOException { save(servers, false); }

    public synchronized void save(List<Server> servers, boolean secure) throws IOException {
        if (secure) saveEncrypted(servers); else { savePlain(servers); cleanupEncrypted(null); }
    }

    private void savePlain(List<Server> servers) throws IOException {
        if (servers.size() > MAX_ENTRIES) throw new IOException("Server catalog cache is too large.");
        Files.createDirectories(file.getParent());
        Path temporary;
        try {
            temporary = Files.createTempFile(file.getParent(), ".server-catalog-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            temporary = Files.createTempFile(file.getParent(), ".server-catalog-", ".tmp");
        }
        try {
            try (OutputStream raw = Files.newOutputStream(temporary);
                 OutputStream bounded = new FilterOutputStream(raw) {
                     private long count;
                     @Override public void write(int value) throws IOException {
                         check(1); out.write(value);
                     }
                     @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                         check(length); out.write(bytes, offset, length);
                     }
                     private void check(int length) throws IOException {
                         if ((count += length) > MAX_BYTES) throw new IOException("Server catalog cache is too large.");
                     }
                 };
                 JsonWriter writer = new JsonWriter(new OutputStreamWriter(bounded, StandardCharsets.UTF_8))) {
                writer.beginObject().name("version").value(1).name("cachedAt").value(Instant.now().toString());
                writer.name("servers").beginArray();
                for (Server server : servers) if (safe(server)) GSON.toJson(server, Server.class, writer);
                writer.endArray().endObject();
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (JsonIOException failure) {
            throw new IOException("Could not write the server catalog cache.",failure);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Server> loadEncrypted(JsonObject metadata) throws IOException {
        if (!metadata.has("encryptedFile")) return List.of();
        String name=metadata.get("encryptedFile").getAsString();
        if(!name.matches("server-catalog-secrets-[a-f0-9]{32}\\.bin"))throw new IOException("Invalid encrypted server catalog reference.");
        Path encrypted=file.resolveSibling(name);
        if(!Files.isRegularFile(encrypted,LinkOption.NOFOLLOW_LINKS) || Files.size(encrypted)>MAX_BYTES+64)
            throw new IOException("The encrypted server catalog is missing or invalid.");
        byte[] key=secrets.load();
        if(key==null || key.length!=32)throw new IOException("The server catalog key is missing. Unlock the operating system credential store or erase the saved catalog.");
        byte[] plaintext=null;
        try {
            makePrivate(encrypted);
            byte[] bytes=Files.readAllBytes(encrypted);
            if(bytes.length<28)throw new IOException("The encrypted server catalog is invalid.");
            javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            plaintext=cipher.doFinal(bytes,12,bytes.length-12);
            JsonObject payload=JsonParser.parseString(new String(plaintext,StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray rows=metadata.getAsJsonArray("servers");
            if(rows.size()>MAX_ENTRIES || payload.size()!=rows.size())throw new IOException("Encrypted server catalog metadata is invalid.");
            List<Server> result=new ArrayList<>();
            for(JsonElement row:rows) {
                JsonObject entry=row.getAsJsonObject();
                Server server=GSON.fromJson(payload.get(entry.get("secretRef").getAsString()),Server.class);
                if(server==null || !server.host().equals(entry.get("host").getAsString()) || server.port()!=entry.get("port").getAsInt()
                        || !server.protocol().equals(entry.get("protocol").getAsString()) || !server.method().equals(entry.get("method").getAsString()))
                    throw new IOException("Encrypted server catalog metadata has been changed.");
                if(safe(server))result.add(server);
            }
            return List.copyOf(result);
        } catch(java.security.GeneralSecurityException|RuntimeException invalid) {
            throw new IOException("The encrypted server catalog could not be authenticated. Saved profiles were not loaded.");
        } finally {Arrays.fill(key,(byte)0);if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
    }

    private void saveEncrypted(List<Server> servers) throws IOException {
        if(servers.size()>MAX_ENTRIES)throw new IOException("Server catalog cache is too large.");
        Files.createDirectories(file.getParent());
        String name="server-catalog-secrets-"+UUID.randomUUID().toString().replace("-","")+".bin";
        Path encrypted=file.resolveSibling(name),temp=null;
        JsonObject metadata=new JsonObject(),payload=new JsonObject();
        metadata.addProperty("version",2);metadata.addProperty("cachedAt",Instant.now().toString());metadata.addProperty("encryptedFile",name);
        JsonArray rows=new JsonArray();metadata.add("servers",rows);
        java.security.SecureRandom random=new java.security.SecureRandom();
        for(Server server:servers) if(safe(server)) {
            byte[] nonce=new byte[32];random.nextBytes(nonce);
            String ref;
            try {ref=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(nonce));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            JsonObject row=new JsonObject();
            row.addProperty("secretRef",ref);row.addProperty("host",server.host());row.addProperty("port",server.port());
            row.addProperty("protocol",server.protocol());row.addProperty("method",server.method());row.addProperty("country",server.country().matches("[A-Z]{2}")?server.country():"");
            rows.add(row);payload.add(ref,GSON.toJsonTree(server));
        }
        byte[] plaintext=boundedJson(payload),key=null;
        boolean committed=false;
        try {
            key=secrets.load();
            if(key==null) {
                if(Files.isRegularFile(file)) {
                    try {
                        if(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt()==2)
                            throw new IOException("The existing encrypted catalog key is missing. Unlock the credential store or erase the saved catalog first.");
                    } catch(RuntimeException invalidMetadata) {throw new IOException("The existing server catalog is invalid. Erase it before creating a new encryption key.");}
                }
                key=new byte[32];random.nextBytes(key);secrets.save(key.clone());
            }
            if(key.length!=32)throw new IOException("The catalog encryption key is invalid.");
            byte[] iv=new byte[12];random.nextBytes(iv);
            javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext=cipher.doFinal(plaintext);
            temp=OsCredentialStore.privateTemp(file.getParent(),".server-catalog-");
            try(OutputStream out=Files.newOutputStream(temp)){out.write(iv);out.write(ciphertext);}
            Files.move(temp,encrypted,StandardCopyOption.ATOMIC_MOVE);temp=null;
            temp=OsCredentialStore.privateTemp(file.getParent(),".server-catalog-");
            Files.write(temp,boundedJson(metadata));
            // Metadata is the commit point: an error leaves the previous generation readable.
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temp=null;
            committed=true;
        } catch(java.security.GeneralSecurityException invalid) {throw new IOException("Could not encrypt the server catalog.");}
        finally {
            Arrays.fill(plaintext,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);
            if(temp!=null)Files.deleteIfExists(temp);
            if(!committed)Files.deleteIfExists(encrypted);
        }
        cleanupEncrypted(name);
    }

    private static byte[] boundedJson(JsonObject object) throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(JsonWriter writer=new JsonWriter(new OutputStreamWriter(new FilterOutputStream(buffer) {
            private int count;
            private void check(int size) throws IOException {if((count+=size)>MAX_BYTES)throw new IOException("Server catalog cache is too large.");}
            @Override public void write(int value)throws IOException{check(1);out.write(value);}
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{check(length);out.write(bytes,offset,length);}
        },StandardCharsets.UTF_8))) {GSON.toJson(object,writer);}
        catch(JsonIOException invalid){throw new IOException("Could not write the server catalog cache.");}
        return buffer.toByteArray();
    }

    private void cleanupEncrypted(String retained) throws IOException {
        if(!Files.isDirectory(file.getParent()))return;
        try(var files=Files.list(file.getParent())) {
            for(Path path:files.filter(path->path.getFileName().toString().matches("server-catalog-secrets-[a-f0-9]{32}\\.bin")
                    && !path.getFileName().toString().equals(retained)).toList())Files.deleteIfExists(path);
        }
    }

    /** Erases only Moondrop's catalog, encrypted generations and catalog key. */
    public synchronized void erase() throws IOException {
        boolean encrypted=false;
        if(Files.isRegularFile(file)) {
            try {encrypted=JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt()==2;}
            catch(RuntimeException invalid){/* Still erase our corrupt catalog. */}
        }
        Files.deleteIfExists(file);cleanupEncrypted(null);
        try {secrets.erase();}catch(IOException unavailable){if(encrypted)throw unavailable;}
    }

    private static boolean safe(Server server) {
        if (server == null || !server.active()) return false;
        try { PublicEndpoints.requirePublicLiteral(server); } catch (IllegalArgumentException unsafe) { return false; }
        try {
            if (server.protocol().equals("shadowsocks")) return Server.supportsEncryptedMethod(server.method());
            server.outbound(); // Enforces an allowed protocol with verified TLS and no routing overrides.
            return true;
        } catch (RuntimeException invalidProfile) { return false; }
    }

    private static void makePrivate(Path path) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException unsupported) { /* Windows uses the user's profile permissions. */ }
    }
}
