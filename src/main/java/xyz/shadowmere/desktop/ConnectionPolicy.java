package xyz.shadowmere.desktop;

import java.io.IOException;
import java.util.*;

/** Pure cached ranking for an intentional single-server connection. Never resolves
 * DNS, opens sockets or tests another candidate after a failure. */
public final class ConnectionPolicy {
    private ConnectionPolicy() { }
    public static Server selectSingle(List<Server> servers, Map<String,ServerPerformance> cached,
                                      String country) throws IOException {
        List<Server> usable=new ArrayList<>();
        for(Server server:servers) {
            if(server==null || !server.active())continue;
            try {
                PublicEndpoints.requirePublicLiteral(server);
                server.outbound();
                usable.add(server);
            } catch(RuntimeException rejected) { /* Invalid cached profiles must never win. */ }
        }
        if(usable.isEmpty())throw new IOException("No compatible servers are available. Refresh the list or enable a protocol.");
        // Stable identity order makes equal candidates deterministic, independent of feed arrival order.
        usable.sort(Comparator.comparing(PerformanceCache::serverKey));
        usable=new ArrayList<>(ServerProximity.prioritize(usable,country));
        usable.sort((left,right)->{
            ServerPerformance a=quality(cached,left),b=quality(cached,right);
            int tier=Integer.compare(tier(a),tier(b));
            if(tier!=0)return tier;
            return a.successful() && b.successful()?ServerPerformance.bestFirst().compare(a,b):0;
        });
        return usable.get(0);
    }
    private static ServerPerformance quality(Map<String,ServerPerformance> cached,Server server) {
        return cached.getOrDefault(PerformanceCache.serverKey(server),ServerPerformance.untested());
    }
    private static int tier(ServerPerformance value) {
        if(value.successful() && value.fresh())return 0;
        if(value.successful() && value.completed())return 1;
        return value.state()==ServerPerformance.State.FAILED?3:2;
    }
}
