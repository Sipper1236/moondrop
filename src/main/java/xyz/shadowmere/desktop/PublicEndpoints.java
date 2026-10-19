package xyz.shadowmere.desktop;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;

/** Network boundary for untrusted feed profiles. Import never performs DNS; connection
 * resolves once, checks every answer, and pins the selected address in the engine config. */
public final class PublicEndpoints {
    private PublicEndpoints() { }
    public static boolean manual(Server server) { return server.id() != null && server.id().startsWith("manual:"); }

    /** Reject special-use literals without contacting a DNS server. */
    public static void requirePublicLiteral(Server server) {
        if (manual(server)) return;
        String host = normalized(server.host());
        if (host.contains("%") || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")
                || !host.contains("." ) && !host.contains(":")) throw rejected();
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try {
                // getByName cannot consult DNS for a numeric IPv6/IPv4 literal.
                if (!isPublic(InetAddress.getByName(host))) throw rejected();
            } catch (IOException invalid) { throw rejected(); }
        }
    }

    /** Validates all answers, including mapped IPv4, before returning a pinned address.
     * Call immediately before opening a socket; never hand the hostname to a second resolver. */
    public static InetAddress resolve(Server server) throws IOException {
        requirePublicLiteral(server);
        InetAddress[] answers = InetAddress.getAllByName(normalized(server.host()));
        return checkedAddress(server, answers);
    }
    public static InetAddress checkedAddress(Server server, InetAddress[] answers) throws IOException {
        requirePublicLiteral(server);
        if (answers == null || answers.length == 0) throw new IOException("The proxy hostname has no usable address.");
        for (InetAddress answer : answers)
            if (answer == null || !manual(server) && !isPublic(answer)) throw new IOException("Public server feeds cannot access private or special-use networks.");
        return answers[0];
    }

    /** Builds a verified outbound with a pinned IP and the original TLS identity. */
    public static JsonObject outbound(Server server) { return outbound(server, null, false); }
    /** Uses answers obtained from a caller-selected network (for example Android's
     * underlying network while VPN lockdown prevents DNS on the default network). */
    public static JsonObject outbound(Server server, InetAddress[] answers) { return outbound(server, answers, true); }
    public static InetAddress resolve(Server server, InetAddress[] answers) throws IOException { return checkedAddress(server, answers); }
    private static JsonObject outbound(Server server, InetAddress[] answers, boolean supplied) {
        JsonObject result = server.outbound();
        try { result.addProperty("server", (supplied ? checkedAddress(server, answers) : resolve(server)).getHostAddress()); }
        catch (IOException invalid) { throw new IllegalArgumentException("Could not safely resolve the proxy endpoint.", invalid); }
        JsonObject tls = result.getAsJsonObject("tls");
        if (tls != null && (!tls.has("server_name") || tls.get("server_name").getAsString().isBlank()))
            tls.addProperty("server_name", normalized(server.host()));
        // Untrusted profiles must never override the address or route selected above.
        for (String key : new String[]{"server_ports", "domain_resolver", "network_strategy", "network_type",
                "fallback_network_type", "bind_address", "inet4_bind_address", "inet6_bind_address"})
            if (result.has(key)) throw new IllegalArgumentException("Unsupported endpoint override in proxy settings.");
        return result;
    }
    private static String normalized(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }
    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Public server feeds cannot access private or special-use networks. Add a private endpoint manually instead.");
    }
    public static boolean isPublic(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 16) {
            boolean mapped = true;
            for (int i=0;i<10;i++) mapped &= b[i] == 0;
            if (mapped && (b[10]&255)==255 && (b[11]&255)==255)
                return ipv4(b,12);
            // The well-known NAT64 prefix is globally routed on IPv6-only mobile
            // networks. It is safe only when its embedded IPv4 destination is public.
            boolean nat64=(b[0]&255)==0 && (b[1]&255)==100 && (b[2]&255)==255 && (b[3]&255)==155;
            for(int i=4;i<12;i++)nat64 &= b[i]==0;
            if(nat64)return ipv4(b,12);
            // Only global unicast 2000::/3; reject documentation, transition,
            // benchmarking, ORCHID and IETF special-purpose allocations.
            int first=b[0]&255, second=b[1]&255;
            if ((first&224)!=32) return false;
            if (first==32 && second==1) {
                int third=b[2]&255, fourth=b[3]&255;
                if (third<2 || third==13 && fourth==184) return false;
            }
            if (first==32 && second==2) return false; // 6to4 can encode a private IPv4 target
            if (first==63 && second==255 && (b[2]&240)==0) return false; // 3fff::/20 documentation
            return true;
        }
        return b.length==4 && ipv4(b,0);
    }
    private static boolean ipv4(byte[] b,int offset) {
        int a=b[offset]&255,c=b[offset+1]&255,d=b[offset+2]&255;
        return !(a==0 || a==10 || a==127 || a>=224 || a==100 && c>=64 && c<=127
                || a==169 && c==254 || a==172 && c>=16 && c<=31 || a==192 && c==168
                || a==192 && c==0 && (d==0 || d==2) || a==192 && c==88 && d==99
                || a==198 && (c==18 || c==19) || a==198 && c==51 && d==100
                || a==203 && c==0 && d==113);
    }
}
