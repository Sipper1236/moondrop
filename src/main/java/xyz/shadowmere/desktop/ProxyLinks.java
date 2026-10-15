package xyz.shadowmere.desktop;

import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict share-link import. Unsupported security and transport options never silently downgrade. */
public final class ProxyLinks {
    private ProxyLinks() { }
    public static Server parse(String id, String link, String country, String location, String checked, boolean active) {
        try {
            if (link != null && link.startsWith("ss://")) return Server.parse(id,link,country,location,checked,active);
            if (link == null || link.length() > 65_536) throw invalid();
            URI uri = URI.create(link);
            String protocol = Objects.requireNonNull(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (protocol.equals("hy2")) protocol = "hysteria2";
            if (!Set.of("vless","trojan","hysteria2","tuic").contains(protocol)) throw invalid();
            String host = uri.getHost();
            if (host != null && host.startsWith("[")) host=host.substring(1,host.length()-1);
            if (host == null || host.isBlank() || (uri.getRawPath()!=null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) throw invalid();
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            Map<String,String> query = query(uri.getRawQuery());
            rejectInsecure(query);
            String auth = decode(Objects.requireNonNull(uri.getRawUserInfo()));
            if (auth.isEmpty()) throw invalid();
            JsonObject out = new JsonObject();
            out.addProperty("type",protocol);out.addProperty("server",host);out.addProperty("server_port",port);
            String secret=auth;
            if (protocol.equals("vless")) {
                out.addProperty("uuid",uuid(auth));
                String encryption=take(query,"encryption","none"); if (!encryption.equals("none"))throw invalid();
                String flow=take(query,"flow","");
                if (!flow.isEmpty()) {if(!flow.equals("xtls-rprx-vision"))throw invalid();out.addProperty("flow",flow);}
                String packet=take(query,"packetEncoding","");
                if(!packet.isEmpty()){if(!Set.of("xudp","packetaddr").contains(packet))throw invalid();out.addProperty("packet_encoding",packet);}
            } else if (protocol.equals("tuic")) {
                int split=auth.indexOf(':'); if(split<1||split==auth.length()-1)throw invalid();
                out.addProperty("uuid",uuid(auth.substring(0,split)));secret=auth.substring(split+1);out.addProperty("password",secret);
                option(out,query,"congestion_control",Set.of("cubic","new_reno","bbr"));
                option(out,query,"udp_relay_mode",Set.of("native","quic"));
            } else out.addProperty("password",auth);
            String security=take(query,"security",protocol.equals("vless")?"":"tls");
            if (!security.equals("tls") && !(protocol.equals("vless")&&security.equals("reality")))throw invalid();
            JsonObject tls=new JsonObject();tls.addProperty("enabled",true);tls.addProperty("insecure",false);
            String sni=alias(query,"sni","peer",host);
            if(sni.isBlank()||sni.chars().anyMatch(c->c<=32))throw invalid();tls.addProperty("server_name",sni);
            String alpn=take(query,"alpn","");if(!alpn.isEmpty()){
                JsonArray values=new JsonArray();for(String value:alpn.split(",",-1)){if(value.isBlank()||value.chars().anyMatch(c->c<=32))throw invalid();values.add(value);}tls.add("alpn",values);
            }
            String fingerprint=take(query,"fp","");
            if(!fingerprint.isEmpty()){
                if(Set.of("hysteria2","tuic").contains(protocol)||!Set.of("chrome","firefox","edge","safari","360","qq","ios","android","random","randomized").contains(fingerprint))throw invalid();
                JsonObject utls=new JsonObject();utls.addProperty("enabled",true);utls.addProperty("fingerprint",fingerprint);tls.add("utls",utls);
            }
            if(security.equals("reality")){
                String key=take(query,"pbk","");
                if(!key.matches("[A-Za-z0-9_-]{43}")||Base64.getUrlDecoder().decode(key).length!=32)throw invalid();
                String sid=take(query,"sid","");if(!sid.matches("(?:[0-9a-fA-F]{2}){0,8}"))throw invalid();
                JsonObject reality=new JsonObject();reality.addProperty("enabled",true);reality.addProperty("public_key",key);reality.addProperty("short_id",sid.toLowerCase(Locale.ROOT));tls.add("reality",reality);
                if(!tls.has("utls")){JsonObject utls=new JsonObject();utls.addProperty("enabled",true);utls.addProperty("fingerprint","chrome");tls.add("utls",utls);}
            }
            out.add("tls",tls);
            if(Set.of("vless","trojan").contains(protocol))transport(out,query,security);
            if(protocol.equals("hysteria2")){
                String obfs=take(query,"obfs","");String password=take(query,"obfs-password","");
                if(!obfs.isEmpty()){if(!Set.of("salamander","gecko").contains(obfs)||password.isEmpty())throw invalid();JsonObject ob=new JsonObject();ob.addProperty("type",obfs);ob.addProperty("password",password);out.add("obfs",ob);}
                else if(!password.isEmpty())throw invalid();
            }
            for(String harmless:List.of("remarks","remark","name","group"))query.remove(harmless);
            if(!query.isEmpty())throw invalid();
            String label=location;
            if((label==null||label.isBlank())&&uri.getRawFragment()!=null)label=decode(uri.getRawFragment());
            return new Server(id,host,port,protocol,secret,country,label,checked,active,protocol,out.toString());
        } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static void transport(JsonObject out,Map<String,String> query,String security){
        String type=take(query,"type","tcp");String header=take(query,"headerType","none");if(!header.equals("none"))throw invalid();
        if(type.equals("tcp"))return;
        if(security.equals("reality")||out.has("flow")||!Set.of("ws","grpc","httpupgrade").contains(type))throw invalid();
        JsonObject transport=new JsonObject();transport.addProperty("type",type);
        if(type.equals("grpc")){
            transport.addProperty("service_name",take(query,"serviceName",""));
            String mode=take(query,"mode","gun");if(!mode.equals("gun"))throw invalid();
        }else{
            String path=take(query,"path","/");if(!path.startsWith("/"))throw invalid();transport.addProperty("path",path);
            String host=take(query,"host","");
            if(!host.isEmpty()){
                if(host.chars().anyMatch(c->c<=32))throw invalid();
                if(type.equals("ws")){JsonObject headers=new JsonObject();headers.addProperty("Host",host);transport.add("headers",headers);}
                else transport.addProperty("host",host);
            }
        }
        out.add("transport",transport);
    }
    private static void rejectInsecure(Map<String,String> query){
        for(String key:List.of("insecure","allowInsecure","skip-cert-verify","allow_insecure","disable_sni")){
            String value=query.remove(key);if(value!=null&&!Set.of("0","false").contains(value.toLowerCase(Locale.ROOT)))throw invalid();
        }
    }
    private static void option(JsonObject out,Map<String,String> query,String key,Set<String> allowed){String value=query.remove(key);if(value!=null){if(!allowed.contains(value))throw invalid();out.addProperty(key,value);}}
    private static String alias(Map<String,String> query,String a,String b,String fallback){String first=query.remove(a),second=query.remove(b);if(first!=null&&second!=null&&!first.equals(second))throw invalid();return first!=null?first:second!=null?second:fallback;}
    private static String uuid(String value){if(!value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String take(Map<String,String> query,String key,String fallback){String value=query.remove(key);return value==null?fallback:value;}
    private static Map<String,String> query(String text){
        Map<String,String> values=new HashMap<>();if(text==null||text.isEmpty())return values;
        for(String field:text.split("&",-1)){String[] pair=field.split("=",2);String key=decode(pair[0]);String value=pair.length==2?decode(pair[1]):"";if(key.isEmpty()||key.chars().anyMatch(c->c<32||c==127)||value.chars().anyMatch(c->c<32||c==127)||values.putIfAbsent(key,value)!=null)throw invalid();}return values;
    }
    private static String decode(String text){return URLDecoder.decode(text.replace("+","%2B"),StandardCharsets.UTF_8);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Unsupported or invalid secure proxy link");}
}
