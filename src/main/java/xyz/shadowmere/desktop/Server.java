package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

public record Server(String id, String host, int port, String method, String password,
                     String country, String location, String lastChecked, boolean active, String protocol, String profileJson) {
    public Server(String id, String host, int port, String method, String password,
                  String country, String location, String lastChecked, boolean active) {
        this(id, host, port, method, password, country, location, lastChecked, active, "shadowsocks", null);
    }
    private static final Set<String> ENCRYPTED_METHODS = Set.of(
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "rc4-md5", "chacha20-ietf", "xchacha20");
    static boolean supportsEncryptedMethod(String method) {return ENCRYPTED_METHODS.contains(method);}
    public Server {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || method == null || method.isBlank()
                || password == null || password.isEmpty()) throw new IllegalArgumentException("Invalid proxy server");
        protocol = protocol == null || protocol.isBlank() ? "shadowsocks" : protocol;
        if (!java.util.Set.of("shadowsocks", "vless", "trojan", "hysteria2", "tuic").contains(protocol))
            throw new IllegalArgumentException("Unsupported proxy protocol");
        if (!protocol.equals("shadowsocks") && profileJson == null) throw new IllegalArgumentException("Missing proxy settings");
        country = country == null ? "" : country;
        location = location == null ? "" : location;
    }

    public String sourceName() {
        if (id != null && id.startsWith("gfp:")) return "GFP";
        if (id != null && id.startsWith("collector:")) return "V2rayCollector";
        if (id != null && id.startsWith("morpheus:")) return "Morpheus";
        return "Shadowmere";
    }
    public boolean usesUdpTransport() {return protocol.equals("hysteria2")||protocol.equals("tuic");}
    public JsonObject outbound() {
        if (!protocol.equals("shadowsocks")) {
            JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
            if(!profile.get("type").getAsString().equals(protocol)||!profile.get("server").getAsString().equals(host)
                    ||profile.get("server_port").getAsInt()!=port)throw new IllegalArgumentException("Inconsistent proxy settings");
            JsonObject tls=profile.getAsJsonObject("tls");
            if(tls==null||!tls.has("enabled")||!tls.get("enabled").getAsBoolean()
                    ||tls.has("insecure")&&tls.get("insecure").getAsBoolean())throw new IllegalArgumentException("Verified TLS protection is required");
            if(profile.has("detour")||profile.has("routing_mark")||profile.has("bind_interface"))throw new IllegalArgumentException("Unsupported routing settings");
            profile.remove("tag");
            return profile;
        }
        JsonObject profile = new JsonObject();
        profile.addProperty("type", "shadowsocks"); profile.addProperty("server", host);
        profile.addProperty("server_port", port); profile.addProperty("method", method);
        profile.addProperty("password", password); return profile;
    }
    public String protocolName() {
        return switch (protocol) {
            case "vless" -> profileJson!=null&&profileJson.contains("\"reality\":")?"VLESS / REALITY":"VLESS"; case "trojan" -> "Trojan";
            case "hysteria2" -> "Hysteria 2"; case "tuic" -> "TUIC"; default -> "Shadowsocks";
        };
    }

    public String displayName() { return location.isBlank() ? host : location; }
    public String countryName() {
        return country.isBlank() ? "Other locations" : new Locale("", country).getDisplayCountry(Locale.ENGLISH);
    }

    public static Server parse(String id, String link, String country, String location, String checked, boolean active) {
        if (link == null || !link.startsWith("ss://")) throw new IllegalArgumentException("Not a Shadowsocks URL");
        String body = link.substring(5).split("#", 2)[0];
        if (body.contains("?")) {
            String query = URLDecoder.decode(body.substring(body.indexOf('?') + 1), StandardCharsets.UTF_8);
            for (String parameter : query.split("&")) {
                if (parameter.split("=", 2)[0].equalsIgnoreCase("plugin"))
                    throw new IllegalArgumentException("Plugin servers are not supported");
            }
            body = body.substring(0, body.indexOf('?'));
        }
        if (!body.contains("@")) body = decode(body);
        int at = body.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("Missing server address");
        String auth = body.substring(0, at);
        if (!auth.contains(":")) auth = decode(auth);
        else auth = URLDecoder.decode(auth.replace("+", "%2B"), StandardCharsets.UTF_8);
        int colon = auth.indexOf(':');
        if (colon < 1) throw new IllegalArgumentException("Missing cipher");
        URI address = URI.create("ss://" + body.substring(at + 1));
        String host = address.getHost();
        if (host != null && host.startsWith("[")) host = host.substring(1, host.length() - 1);
        return new Server(id, host, address.getPort(), auth.substring(0, colon), auth.substring(colon + 1),
                country, location, checked, active);
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
    }

    // A record's generated toString would expose its password in logs.
    @Override public String toString() { return displayName() + " (" + host + ":" + port + ")"; }
}
