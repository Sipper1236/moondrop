package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared endpoint boundary for privileged full-device tunnels. Never resolves DNS while blocked. */
final class TunnelEndpoints {
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    private final Path data;
    private final Resolver resolver;
    TunnelEndpoints(Path data) { this(data,InetAddress::getAllByName); }
    TunnelEndpoints(Path data,Resolver resolver) { this.data=data;this.resolver=resolver; }

    JsonObject outbound(Server server,boolean blocked) throws Exception {
        String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (server.host()+":"+server.port()+":"+server.protocol()).getBytes(StandardCharsets.UTF_8)));
        Path cache=data.resolve("tunnel-endpoints.json");
        JsonObject pins=new JsonObject();
        if(Files.isRegularFile(cache,LinkOption.NOFOLLOW_LINKS)&&Files.size(cache)<1024*1024)
            try {pins=JsonParser.parseString(Files.readString(cache)).getAsJsonObject();}catch(RuntimeException ignored) { }
        String host=server.host();
        if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        InetAddress[] addresses;
        if(blocked) {
            if(literal(host))addresses=new InetAddress[]{InetAddress.getByName(host)};
            else if(pins.has(key)) {
                String saved;
                try {saved=pins.get(key).getAsString();}catch(RuntimeException invalid){throw new IOException("The saved tunnel endpoint is invalid.",invalid);}
                if(!literal(saved))throw new IOException("The saved tunnel endpoint is invalid.");
                addresses=new InetAddress[]{InetAddress.getByName(saved)};
            } else throw new IOException("This hostname has no saved endpoint. Restore direct internet in Settings before connecting to a new server.");
        } else {
            // Validate feed literals before consulting DNS; manual endpoints also require public addresses here.
            PublicEndpoints.requirePublicLiteral(server);
            addresses=literal(host)?new InetAddress[]{InetAddress.getByName(host)}:resolver.resolve(host);
        }
        if(addresses==null||addresses.length==0)throw new IOException("The proxy hostname has no usable address.");
        for(InetAddress address:addresses)
            if(address==null||!PublicEndpoints.isPublic(address))throw new IOException("Full-device mode currently requires a public endpoint.");
        JsonObject outbound=PublicEndpoints.outbound(server,addresses);
        if(!blocked) {
            pins.addProperty(key,addresses[0].getHostAddress());Files.createDirectories(data);
            Path temp=Files.createTempFile(data,".tunnel-endpoints-",".tmp");
            try {
                try {Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored) { }
                Files.writeString(temp,pins.toString());
                Files.move(temp,cache,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally {Files.deleteIfExists(temp);}
        }
        return outbound;
    }
    private static boolean literal(String host) { return !host.contains("%")&&(host.contains(":")||host.matches("[0-9.]+")); }
}
