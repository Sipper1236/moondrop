package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reversible per-user Linux desktop, user-service, and shell proxy integration. */
final class LinuxSessionProxy {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> GNOME = List.of(
        "org.gnome.system.proxy|use-same-proxy", "org.gnome.system.proxy|ignore-hosts",
        "org.gnome.system.proxy|autoconfig-url", "org.gnome.system.proxy.http|enabled",
        "org.gnome.system.proxy.http|use-authentication", "org.gnome.system.proxy.http|host",
        "org.gnome.system.proxy.http|port", "org.gnome.system.proxy.https|host",
        "org.gnome.system.proxy.https|port", "org.gnome.system.proxy.socks|host",
        "org.gnome.system.proxy.socks|port", "org.gnome.system.proxy|mode");
    private static final List<String> ENV = List.of("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "no_proxy", "NO_PROXY");
    private static final String START = "# >>> MOONDROP PROXY >>>", END = "# <<< MOONDROP PROXY <<<";
    private final Path recovery, shellFile, fishFile, fishHook;
    private final Path home;

    LinuxSessionProxy(Path dataDir) {
        recovery = dataDir.toAbsolutePath().resolve("linux-proxy-recovery.json");
        home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = (xdg == null || xdg.isBlank() ? home.resolve(".config") : Path.of(xdg)).toAbsolutePath();
        shellFile = config.resolve("moondrop/proxy-env.sh"); fishFile = config.resolve("moondrop/proxy-env.fish");
        fishHook = config.resolve("fish/conf.d/moondrop-proxy.fish");
    }

    boolean hasRecovery() { return Files.exists(recovery); }

    synchronized void enable(String host, int port) throws Exception {
        if (hasRecovery()) restore();
        if (Files.exists(shellFile) || Files.exists(fishFile)) throw new IOException("A stale Moondrop proxy environment file exists; disconnect or remove it before reconnecting.");
        JsonObject state = new JsonObject(); state.addProperty("version", 1);
        JsonObject hooks = new JsonObject();
        for (String name : List.of(".profile", ".bashrc", ".zshrc")) hooks.addProperty(home.resolve(name).toString(), !Files.exists(home.resolve(name)));
        hooks.addProperty(fishHook.toString(), !Files.exists(fishHook)); state.add("hooks", hooks);
        JsonObject gnome = snapshotGnome(); if (gnome != null) state.add("gnome", gnome);
        JsonObject userEnv = snapshotUserEnvironment(); if (userEnv != null) state.add("userEnvironment", userEnv);
        JsonObject appliedEnv = envValues(host, port); state.add("appliedEnvironment", appliedEnv);
        save(state);
        try {
            privateWrite(shellFile, shellEnvironment(host, port)); privateWrite(fishFile, fishEnvironment(host, port));
            installHooks();
            if (gnome != null) setGnome(host, port);
            if (userEnv != null) setUserEnvironment(appliedEnv);
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            try { restore(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw new IOException("Could not apply Linux proxy settings. " + (hasRecovery() ? "Recovery data was retained." : "Previous settings were restored."), failure);
        }
    }

    synchronized void restore() throws Exception {
        if (!hasRecovery()) return;
        JsonObject state = JSON.fromJson(Files.readString(recovery), JsonObject.class);
        if (state == null || !state.has("version") || state.get("version").getAsInt() != 1) throw new IOException("Linux proxy recovery data is invalid; it was retained.");
        List<Exception> errors = new ArrayList<>();
        if (state.has("gnome")) attempt(errors, () -> restoreGnome(state.getAsJsonObject("gnome")));
        if (state.has("userEnvironment")) attempt(errors, () -> restoreUserEnvironment(state.getAsJsonObject("userEnvironment"), state.getAsJsonObject("appliedEnvironment")));
        attempt(errors, () -> removeHooks(state.getAsJsonObject("hooks")));
        attempt(errors, () -> removeOwned(shellFile, shellEnvironment("127.0.0.1", 17890)));
        attempt(errors, () -> removeOwned(fishFile, fishEnvironment("127.0.0.1", 17890)));
        if (!errors.isEmpty()) { IOException e = new IOException("Some Linux proxy settings could not be restored. Recovery data was retained; retry Disconnect."); errors.forEach(e::addSuppressed); throw e; }
        Files.deleteIfExists(recovery);
    }

    private JsonObject snapshotGnome() {
        if (!available("gsettings")) return null;
        try { JsonObject old = new JsonObject(); for (String key : GNOME) { String[] p=parts(key); old.addProperty(key, run(List.of("gsettings","get",p[0],p[1])).strip()); } return old; }
        catch (Exception unavailable) { return null; }
    }
    private void setGnome(String host, int port) throws Exception {
        for (String key : GNOME) { String[] p=parts(key); String value=switch(key) {
            case "org.gnome.system.proxy|use-same-proxy" -> "true";
            case "org.gnome.system.proxy|ignore-hosts" -> "['localhost', '127.*', '::1']";
            case "org.gnome.system.proxy|autoconfig-url" -> "''";
            case "org.gnome.system.proxy.http|enabled" -> "true";
            case "org.gnome.system.proxy.http|use-authentication" -> "false";
            case "org.gnome.system.proxy.http|host", "org.gnome.system.proxy.https|host", "org.gnome.system.proxy.socks|host" -> "'"+host+"'";
            case "org.gnome.system.proxy.http|port", "org.gnome.system.proxy.https|port", "org.gnome.system.proxy.socks|port" -> Integer.toString(port);
            case "org.gnome.system.proxy|mode" -> "'manual'"; default -> throw new IllegalStateException(); };
            run(List.of("gsettings","set",p[0],p[1],value));
        }
    }
    private void restoreGnome(JsonObject old) throws Exception {
        List<Exception> errors=new ArrayList<>();
        for (String key:GNOME) attempt(errors,()->{String[] p=parts(key); String current=run(List.of("gsettings","get",p[0],p[1])).strip();
            String expected=gnomeValue(key); if(current.equals(expected))run(List.of("gsettings","set",p[0],p[1],old.get(key).getAsString()));});
        if(!errors.isEmpty()){IOException e=new IOException("Could not restore GNOME proxy settings.");errors.forEach(e::addSuppressed);throw e;}
    }
    private JsonObject snapshotUserEnvironment() {
        if (!available("systemctl")) return null;
        try { String out=run(List.of("systemctl","--user","show-environment")); Map<String,String> current=parseEnv(out);
            for(String k:ENV)if(current.containsKey(k)&&!current.get(k).isBlank())return null;
            JsonObject old=new JsonObject(); for(String k:ENV)old.add(k,JsonNull.INSTANCE); return old;
        } catch(Exception unavailable){return null;}
    }
    private void setUserEnvironment(JsonObject values)throws Exception {
        List<String> command=new ArrayList<>(List.of("systemctl","--user","set-environment"));
        for(var e:values.entrySet())command.add(e.getKey()+"="+e.getValue().getAsString()); run(command);
        if(available("dbus-update-activation-environment")){List<String> dbus=new ArrayList<>(List.of("dbus-update-activation-environment","--systemd"));for(var e:values.entrySet())dbus.add(e.getKey()+"="+e.getValue().getAsString());run(dbus);}
    }
    private void restoreUserEnvironment(JsonObject old,JsonObject applied)throws Exception {
        Map<String,String> current=parseEnv(run(List.of("systemctl","--user","show-environment")));
        List<String> set=new ArrayList<>(List.of("systemctl","--user","set-environment")),unset=new ArrayList<>(List.of("systemctl","--user","unset-environment"));
        List<String> restoreDbus=new ArrayList<>(List.of("dbus-update-activation-environment","--systemd"));
        for(String k:ENV)if(current.getOrDefault(k,"").equals(applied.get(k).getAsString())){JsonElement previous=old.get(k);if(previous==null||previous.isJsonNull()){unset.add(k);restoreDbus.add("--unset="+k);}else{set.add(k+"="+previous.getAsString());restoreDbus.add(k+"="+previous.getAsString());}}
        if(unset.size()>2)run(unset);if(set.size()>2)run(set);if(available("dbus-update-activation-environment")&&restoreDbus.size()>2)run(restoreDbus);
    }

    private void installHooks() throws IOException {
        for(String name:List.of(".profile",".bashrc",".zshrc")) append(home.resolve(name),posixHook());
        Files.createDirectories(fishHook.getParent());
        append(fishHook,START+"\nset -l _moondrop_proxy_file \""+fishFile+"\"\nif test -r \"$_moondrop_proxy_file\"; source \"$_moondrop_proxy_file\"; else if set -q MOONDROP_PROXY_ACTIVE; and test \"$MOONDROP_PROXY_ACTIVE\" = 1; set -e http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY no_proxy NO_PROXY MOONDROP_PROXY_ACTIVE; end; end\n"+END+"\n");
    }
    private static String posixHook(){return START+"\n_moondrop_proxy_file=\"${XDG_CONFIG_HOME:-$HOME/.config}/moondrop/proxy-env.sh\"\nif [ -r \"$_moondrop_proxy_file\" ]; then . \"$_moondrop_proxy_file\"; elif [ \"${MOONDROP_PROXY_ACTIVE-}\" = 1 ]; then unset http_proxy https_proxy all_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY no_proxy NO_PROXY MOONDROP_PROXY_ACTIVE; fi\nunset _moondrop_proxy_file\n"+END+"\n";}
    private static void append(Path path,String block)throws IOException{Files.createDirectories(path.getParent());String old=Files.exists(path)?Files.readString(path):"";if(old.contains(START))throw new IOException("A Moondrop shell hook already exists in "+path.getFileName());Files.writeString(path,old+(old.isEmpty()||old.endsWith("\n")?"":"\n")+block,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}
    private void removeHooks(JsonObject hooks)throws Exception{List<Exception> errors=new ArrayList<>();for(String name:hooks.keySet())attempt(errors,()->{Path p=Path.of(name);if(!Files.exists(p))return;String text=Files.readString(p);String clean=removeBlock(text);if(!clean.equals(text)){if(hooks.get(name).getAsBoolean()&&clean.isBlank())Files.delete(p);else Files.writeString(p,clean,StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);}});if(!errors.isEmpty()){IOException e=new IOException("Could not remove Linux shell hooks.");errors.forEach(e::addSuppressed);throw e;}}
    private static String removeBlock(String text)throws IOException{int from=text.indexOf(START);if(from<0)return text;int to=text.indexOf(END,from);if(to<0||text.indexOf(START,from+START.length())>=0)throw new IOException("Moondrop shell hook was edited; recovery was retained.");to+=END.length();if(to<text.length()&&text.charAt(to)=='\n')to++;return text.substring(0,from)+text.substring(to);}

    private void save(JsonObject state)throws IOException{Files.createDirectories(recovery.getParent());Path tmp=Files.createTempFile(recovery.getParent(),".linux-proxy-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.writeString(tmp,JSON.toJson(state));Files.move(tmp,recovery,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
    private static void privateWrite(Path p,String text)throws IOException{Files.createDirectories(p.getParent());Files.writeString(p,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}
    private static void removeOwned(Path p,String expected)throws IOException{if(!Files.exists(p))return;if(!Files.readString(p).equals(expected))throw new IOException("Moondrop proxy environment file was edited; recovery was retained.");Files.delete(p);}
    private static JsonObject envValues(String host,int port){String http="http://"+host+":"+port,socks="socks5h://"+host+":"+port;JsonObject o=new JsonObject();o.addProperty("http_proxy",http);o.addProperty("https_proxy",http);o.addProperty("all_proxy",socks);o.addProperty("HTTP_PROXY",http);o.addProperty("HTTPS_PROXY",http);o.addProperty("ALL_PROXY",socks);o.addProperty("no_proxy","localhost,127.0.0.1,::1");o.addProperty("NO_PROXY","localhost,127.0.0.1,::1");return o;}
    private static String shellEnvironment(String h,int p){JsonObject env=envValues(h,p);StringBuilder b=new StringBuilder("export MOONDROP_PROXY_ACTIVE=1\n");for(var e:env.entrySet())b.append("export ").append(e.getKey()).append("='").append(e.getValue().getAsString()).append("'\n");return b.toString();}
    private static String fishEnvironment(String h,int p){JsonObject env=envValues(h,p);StringBuilder b=new StringBuilder("set -gx MOONDROP_PROXY_ACTIVE 1\n");for(var e:env.entrySet())b.append("set -gx ").append(e.getKey()).append(" '").append(e.getValue().getAsString()).append("'\n");return b.toString();}
    private static String gnomeValue(String k){return switch(k){case "org.gnome.system.proxy|use-same-proxy"->"true";case "org.gnome.system.proxy|ignore-hosts"->"['localhost', '127.*', '::1']";case "org.gnome.system.proxy|autoconfig-url"->"''";case "org.gnome.system.proxy.http|enabled"->"true";case "org.gnome.system.proxy.http|use-authentication"->"false";case "org.gnome.system.proxy.http|host","org.gnome.system.proxy.https|host","org.gnome.system.proxy.socks|host"->"'127.0.0.1'";case "org.gnome.system.proxy.http|port","org.gnome.system.proxy.https|port","org.gnome.system.proxy.socks|port"->"17890";case "org.gnome.system.proxy|mode"->"'manual'";default->"";};}
    private static String[] parts(String key){return key.split("\\|",2);}
    private static Map<String,String> parseEnv(String text){Map<String,String> m=new HashMap<>();for(String line:text.split("\\R")){int i=line.indexOf('=');if(i>0)m.put(line.substring(0,i),line.substring(i+1));}return m;}
    private static boolean available(String exe){String path=System.getenv("PATH");if(path==null)return false;for(String dir:path.split(java.io.File.pathSeparator))if(Files.isExecutable(Path.of(dir).resolve(exe)))return true;return false;}
    private static String run(List<String> args)throws Exception{Process p=new ProcessBuilder(args).redirectErrorStream(true).start();try{if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Command timed out: "+args.get(0));}byte[] b=p.getInputStream().readNBytes(8193);if(p.exitValue()!=0||b.length>8192)throw new IOException("Command failed: "+args.get(0));return new String(b,StandardCharsets.UTF_8);}catch(InterruptedException e){p.destroyForcibly();Thread.currentThread().interrupt();throw e;}finally{if(p.isAlive())p.destroyForcibly();}}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void attempt(List<Exception> errors,Action action){try{action.run();}catch(Exception e){errors.add(e);}}
}
