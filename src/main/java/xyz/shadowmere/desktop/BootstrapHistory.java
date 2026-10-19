package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Actual successful entry connections, deliberately separate from performance tests. */
final class BootstrapHistory {
    private final Path file;
    private final Map<String,Long> successes=new HashMap<>();
    private final Set<String> failed=new HashSet<>();
    BootstrapHistory(Path data) {
        file=data.resolve("bootstrap-history.json");
        try {
            if(Files.isRegularFile(file)&&Files.size(file)<64*1024) {
                JsonObject entries=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for(var entry:entries.entrySet())if(entry.getKey().matches("[a-f0-9]{64}"))successes.put(entry.getKey(),entry.getValue().getAsLong());
            }
        }catch(IOException|RuntimeException ignored){successes.clear();}
    }
    synchronized void succeeded(Server server)throws IOException {
        String key=PerformanceCache.serverKey(server);successes.put(key,Instant.now().toEpochMilli());failed.remove(key);
        List<String> keep=successes.keySet().stream().sorted(Comparator.comparingLong((String k)->successes.get(k)).reversed()).limit(8).toList();
        successes.keySet().retainAll(keep);
        JsonObject root=new JsonObject();successes.forEach(root::addProperty);
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),".bootstrap-",".tmp");
        try {Files.writeString(temp,root.toString());Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temp);}
    }
    synchronized void failed(Server server){failed.add(PerformanceCache.serverKey(server));}
    synchronized List<Server> preferred(List<Server> servers) {
        return servers.stream().filter(s->successes.containsKey(PerformanceCache.serverKey(s))&&!failed.contains(PerformanceCache.serverKey(s)))
            .sorted(Comparator.comparingLong((Server s)->successes.get(PerformanceCache.serverKey(s))).reversed()).toList();
    }
    synchronized List<Server> retain(List<Server> saved,List<Server> fresh) {
        LinkedHashMap<String,Server> merged=new LinkedHashMap<>();
        for(Server s:saved)if(PublicEndpoints.manual(s))merged.put(PerformanceCache.serverKey(s),s);
        for(Server s:fresh)merged.putIfAbsent(PerformanceCache.serverKey(s),s);
        // Feed disappearance is not proof that a proven entry stopped working.
        for(Server s:saved)if(PublicEndpoints.manual(s)||successes.containsKey(PerformanceCache.serverKey(s)))merged.putIfAbsent(PerformanceCache.serverKey(s),s);
        return List.copyOf(merged.values());
    }
    synchronized void erase()throws IOException {Files.deleteIfExists(file);successes.clear();failed.clear();}
}
