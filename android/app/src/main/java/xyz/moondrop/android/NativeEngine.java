package xyz.moondrop.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;

import xyz.moondrop.nativebridge.AndroidPlatform;
import xyz.moondrop.nativebridge.Nativebridge;

/** One in-process sing-box engine shared by testing and the VPN session. */
public final class NativeEngine {
    private static Platform current;

    private NativeEngine() {}

    public static synchronized void start(Context context, VpnService service, String config) throws Exception {
        if (current != null) throw new IllegalStateException("A connection or server test is already running");
        go.Seq.setContext(context.getApplicationContext());
        Platform platform = new Platform(context.getApplicationContext(), service);
        try {
            platform.register();
            Nativebridge.start(config, context.getFilesDir().getAbsolutePath(),
                    context.getCacheDir().getAbsolutePath(), platform);
            current = platform;
        } catch (Exception | LinkageError error) {
            platform.close();
            throw error;
        }
    }

    public static synchronized void stop() {
        try {
            Nativebridge.stop();
        } finally {
            Platform platform = current;
            current = null;
            if (platform != null) platform.close();
        }
    }

    private static final class Platform implements AndroidPlatform {
        private final Context context;
        private final VpnService vpn;
        private final ConnectivityManager connectivity;
        private ParcelFileDescriptor tunnel;
        private boolean registered;
        private volatile boolean closed;
        private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { updateNetwork(); }
            @Override public void onLost(Network network) { updateNetwork(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { updateNetwork(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { updateNetwork(); }
        };

        Platform(Context context, VpnService vpn) {
            this.context = context;
            this.vpn = vpn;
            connectivity = context.getSystemService(ConnectivityManager.class);
        }

        void register() {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
            registered = true;
        }

        void updateNetwork() {
            if (closed) return;
            Network network = underlying();
            if (vpn != null) vpn.setUnderlyingNetworks(network == null ? null : new Network[]{network});
            Nativebridge.updateNetwork(defaultInterfaceName(), defaultInterfaceIndex(), defaultInterfaceMetered());
        }

        Network underlying() {
            Network active = connectivity.getActiveNetwork();
            if (usable(active)) return active;
            Network fallback = null;
            for (Network network : connectivity.getAllNetworks()) {
                if (!usable(network)) continue;
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    return network;
                fallback = network;
            }
            return fallback;
        }

        boolean usable(Network network) {
            if (network == null) return false;
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            return capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        @Override public boolean protect(int fd) { return vpn == null || vpn.protect(fd); }

        @Override public String lookup(String domain, String network) throws Exception {
            Network underlying = underlying();
            if (underlying == null) throw new IOException("No internet connection is available");
            StringBuilder result = new StringBuilder();
            for (InetAddress address : underlying.getAllByName(domain)) {
                if ("ip4".equals(network) && !(address instanceof Inet4Address)) continue;
                if ("ip6".equals(network) && !(address instanceof Inet6Address)) continue;
                if (result.length() > 0) result.append('\n');
                result.append(address.getHostAddress());
            }
            return result.toString();
        }

        @Override public int openTun() throws Exception {
            if (vpn == null) throw new IOException("VPN permission is required to route device traffic");
            if (tunnel != null) throw new IOException("A VPN interface is already open");
            VpnService.Builder builder = vpn.new Builder().setSession("Moondrop")
                    .setMtu(9000)
                    .addAddress("172.19.0.1", 30)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addDnsServer("172.19.0.2")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(context.getPackageName());
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            Network underlying = underlying();
            if (underlying != null) builder.setUnderlyingNetworks(new Network[]{underlying});
            tunnel = builder.establish();
            if (tunnel == null) throw new IOException("Android did not grant the VPN connection");
            return tunnel.getFd();
        }

        @Override public String defaultInterfaceName() {
            Network network = underlying();
            LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
            return properties == null || properties.getInterfaceName() == null ? "" : properties.getInterfaceName();
        }

        @Override public int defaultInterfaceIndex() {
            try {
                String name = defaultInterfaceName();
                NetworkInterface network = name.isEmpty() ? null : NetworkInterface.getByName(name);
                return network == null ? -1 : network.getIndex();
            } catch (Exception error) { return -1; }
        }

        @Override public boolean defaultInterfaceMetered() {
            Network network = underlying();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            return capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        @Override public String interfacesJSON() {
            JSONArray result = new JSONArray();
            for (Network network : connectivity.getAllNetworks()) {
                try {
                    LinkProperties link = connectivity.getLinkProperties(network);
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                    if (link == null || caps == null || link.getInterfaceName() == null) continue;
                    NetworkInterface netInterface = NetworkInterface.getByName(link.getInterfaceName());
                    if (netInterface == null) continue;
                    JSONArray addresses = new JSONArray();
                    for (LinkAddress address : link.getLinkAddresses()) {
                        String host = address.getAddress().getHostAddress();
                        int zone = host.indexOf('%');
                        addresses.put((zone < 0 ? host : host.substring(0, zone)) + "/" + address.getPrefixLength());
                    }
                    JSONArray dns = new JSONArray();
                    for (InetAddress address : link.getDnsServers()) dns.put(address.getHostAddress());
                    JSONArray gateways = new JSONArray();
                    for (RouteInfo route : link.getRoutes())
                        if (route.isDefaultRoute() && route.getGateway() != null && !route.getGateway().isAnyLocalAddress())
                            gateways.put(route.getGateway().getHostAddress());
                    int flags = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            ? OsConstants.IFF_UP | OsConstants.IFF_RUNNING : 0;
                    if (netInterface.isLoopback()) flags |= OsConstants.IFF_LOOPBACK;
                    if (netInterface.isPointToPoint()) flags |= OsConstants.IFF_POINTOPOINT;
                    if (netInterface.supportsMulticast()) flags |= OsConstants.IFF_MULTICAST;
                    int type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 0
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? 1
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 2 : 3;
                    result.put(new JSONObject().put("name", link.getInterfaceName())
                            .put("index", netInterface.getIndex()).put("mtu", netInterface.getMTU())
                            .put("flags", flags).put("type", type).put("addresses", addresses)
                            .put("dns", dns).put("gateways", gateways)
                            .put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)));
                } catch (Exception ignored) {
                    // A network may disappear between the Android queries.
                }
            }
            return result.toString();
        }

        void close() {
            closed = true;
            if (registered) {
                try { connectivity.unregisterNetworkCallback(callback); } catch (IllegalArgumentException ignored) {}
                registered = false;
            }
            if (tunnel != null) {
                try { tunnel.close(); } catch (IOException ignored) {}
                tunnel = null;
            }
        }
    }
}
