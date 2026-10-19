package xyz.moondrop.android;

import android.content.Context;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import xyz.shadowmere.desktop.Server;

/** Exact previously approved pinned route, encrypted for OS always-on restoration. */
final class SessionStore {
    record Session(Server server,String configuration){}
    private final File file;
    private final ProfileCipher cipher=new ProfileCipher();
    SessionStore(Context context){file=new File(context.getFilesDir(),"last-session-v1.bin");}
    synchronized void save(Server server,String configuration)throws IOException{
        File temp=new File(file.getParentFile(),file.getName()+".tmp");boolean replaced=false;
        try{JsonObject value=new JsonObject();value.add("server",new Gson().toJsonTree(server));value.addProperty("configuration",configuration);
            try(OutputStream raw=new FileOutputStream(temp);OutputStream encrypted=cipher.encrypt(raw);Writer writer=new OutputStreamWriter(encrypted,StandardCharsets.UTF_8)){writer.write(value.toString());}
            if(!temp.renameTo(file))throw new IOException("Could not save the approved VPN route");replaced=true;
        }finally{if(!replaced)temp.delete();}
    }
    synchronized Session load()throws IOException{
        if(!file.exists())throw new IOException("Connect once in Moondrop before enabling Always-on VPN.");
        if(file.length()>1024*1024)throw new IOException("The approved VPN route is unavailable. Open Moondrop and reconnect.");
        try(InputStream raw=new FileInputStream(file);InputStream decrypted=cipher.decrypt(raw);Reader reader=new InputStreamReader(decrypted,StandardCharsets.UTF_8)){
            JsonObject value=JsonParser.parseReader(reader).getAsJsonObject();Server server=new Gson().fromJson(value.get("server"),Server.class);String config=value.get("configuration").getAsString();return new Session(server,config);
        }catch(RuntimeException failure){throw new IOException("The approved VPN route could not be restored. Open Moondrop and reconnect.",failure);}
    }
    synchronized void erase()throws IOException{for(File target:new File[]{file,new File(file.getParentFile(),file.getName()+".tmp")})if(target.exists()&&!target.delete())throw new IOException("Could not erase the saved VPN route");}
}
