package xyz.moondrop.android;

import java.util.List;
import java.util.Map;
import xyz.shadowmere.desktop.Server;

/** Every observer receives a complete immutable snapshot on the main thread. */
public final class AppState {
    public final List<Server> servers;
    public final Map<String,Measurement> measurements;
    public final boolean loading,testing,connected,connecting;
    public final Server connectedServer;
    public final String title,detail,error;
    public final int checked,working;
    public AppState(List<Server> servers,Map<String,Measurement> measurements,boolean loading,boolean testing,
                    boolean connected,boolean connecting,Server connectedServer,String title,String detail,String error,int checked,int working) {
        this.servers=List.copyOf(servers);this.measurements=Map.copyOf(measurements);this.loading=loading;this.testing=testing;
        this.connected=connected;this.connecting=connecting;this.connectedServer=connectedServer;
        this.title=title;this.detail=detail;this.error=error;this.checked=checked;this.working=working;
    }
}
