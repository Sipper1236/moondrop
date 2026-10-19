package xyz.moondrop.android;

import com.google.gson.*;
import java.util.*;
import xyz.shadowmere.desktop.Server;

/** Actual direct connection successes, deliberately independent of benchmark scores. */
final class BootstrapHistory {
    static final int LIMIT=12;
    private final Map<String,Long> successes=new HashMap<>();
    private final Set<String> failed=new HashSet<>();
    BootstrapHistory(String json) {
        try {JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            for(var row:root.entrySet())if(row.getKey().matches("[a-f0-9]{64}")&&row.getValue().getAsLong()>0)successes.put(row.getKey(),row.getValue().getAsLong());
            trim();
        }catch(RuntimeException ignored){}
    }
    synchronized void connected(Server server,long now){successes.put(ServerRepository.key(server),now);failed.remove(ServerRepository.key(server));trim();}
    synchronized void failed(Server server){failed.add(ServerRepository.key(server));}
    synchronized boolean contains(Server server){return successes.containsKey(ServerRepository.key(server))&&!failed.contains(ServerRepository.key(server));}
    synchronized void clear(){successes.clear();failed.clear();}
    synchronized String json(){return new Gson().toJson(successes);}
    synchronized List<Server> rank(List<Server> candidates){List<Server> result=new ArrayList<>(candidates);result.sort(Comparator.comparingLong((Server s)->failed.contains(ServerRepository.key(s))?0L:successes.getOrDefault(ServerRepository.key(s),0L)).reversed());return result;}
    synchronized List<Server> retain(List<Server> fresh,List<Server> saved){Map<String,Server> result=new LinkedHashMap<>();for(Server server:fresh)result.put(ServerRepository.key(server),server);for(Server server:saved)if(server.id().startsWith("import:"))result.put(ServerRepository.key(server),server);else if(successes.containsKey(ServerRepository.key(server)))result.putIfAbsent(ServerRepository.key(server),server);return List.copyOf(result.values());}
    private void trim(){if(successes.size()>LIMIT){List<String> keys=new ArrayList<>(successes.keySet());keys.sort(Comparator.comparingLong(successes::get));for(int i=0;i<keys.size()-LIMIT;i++)successes.remove(keys.get(i));}}
}
