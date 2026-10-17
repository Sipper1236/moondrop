package xyz.moondrop.android;

import android.app.Activity;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Runs in the instrumentation APK's separate UID; absent from release builds. */
public final class RoutingProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try(FileOutputStream ignored=openFileOutput("routing-result.txt",MODE_PRIVATE)){}catch(IOException ignored){}
        new Thread(()->{
            String result;
            try(Socket socket=new Socket()){
                socket.connect(new InetSocketAddress("198.18.0.42",18080),5000);socket.setSoTimeout(5000);
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=socket.getInputStream().read(buffer))!=-1)bytes.write(buffer,0,count);
                result=bytes.toString("UTF-8");
            }catch(Exception failure){result=failure.toString();}
            try(FileOutputStream output=openFileOutput("routing-result.txt",MODE_PRIVATE)){output.write(result.getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
            runOnUiThread(this::finish);
        },"vpn-route-probe").start();
    }
}
