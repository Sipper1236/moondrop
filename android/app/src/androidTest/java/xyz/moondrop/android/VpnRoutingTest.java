package xyz.moondrop.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.os.Build;
import android.annotation.SuppressLint;
import java.util.concurrent.TimeUnit;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real TUN traffic from a different UID, decrypted by a controlled host Shadowsocks server. */
@RunWith(AndroidJUnit4.class)
public final class VpnRoutingTest {
    @Rule public ActivityTestRule<MainActivity> activity=new ActivityTestRule<>(MainActivity.class,false,false);
    @SuppressLint("NewApi")
    @Test public void anotherApplicationRoutesThroughTheEncryptedVpn() throws Exception {
        String ssPort=InstrumentationRegistry.getArguments().getString("fixtureSsPort");
        assumeTrue("Bidirectional shell pipes require Android 12+",Build.VERSION.SDK_INT>=31);
        assumeTrue("Run scripts/vpn-fixture.py and pass fixtureSsPort",ssPort!=null);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("testing",false).commit();
        activity.launchActivity(null);
        shell(instrumentation,"appops set "+context.getPackageName()+" ACTIVATE_VPN allow");
        assertNull("VPN access must be authorized by the test harness",VpnService.prepare(context));
        Server endpoint=new Server("vpn-fixture","10.0.2.2",Integer.parseInt(ssPort),"chacha20-ietf-poly1305","moondrop-local-routing-fixture","CA","Local fixture","",true);
        FixtureVpnService.reset();
        Intent service=new Intent(context,FixtureVpnService.class).putExtra("config",AppController.configuration(endpoint,0,true));
        try{
            context.startForegroundService(service);
            assertTrue("VPN engine did not start",FixtureVpnService.ready.await(15,TimeUnit.SECONDS));
            if(FixtureVpnService.failure!=null)throw new AssertionError("VPN engine start failed",FixtureVpnService.failure);
            // run-as uses the separate, debuggable instrumentation APK's UID. Moondrop's own UID is excluded.
            // 198.18.0.42 has no host listener or direct route: only the remote fixture remaps it to HTTP.
            String command="run-as "+instrumentation.getContext().getPackageName()+" /system/bin/toybox nc -q 2 -w 5 198.18.0.42 18080";
            ParcelFileDescriptor[] pipes=instrumentation.getUiAutomation().executeShellCommandRw(command);
            try(FileOutputStream input=new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])){
                input.write("GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            String response;
            try(FileInputStream output=new ParcelFileDescriptor.AutoCloseInputStream(pipes[0]);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;while((count=output.read(buffer))!=-1)bytes.write(buffer,0,count);
                response=bytes.toString("UTF-8");
            }
            assertTrue("Traffic did not pass through the VPN: "+response,response.contains("moondrop-vpn-routing-passed"));
        }finally{
            context.stopService(service);
            instrumentation.waitForIdleSync();NativeEngine.stop();
            shell(instrumentation,"appops set "+context.getPackageName()+" ACTIVATE_VPN default");
        }
    }
    private static String shell(Instrumentation instrumentation,String command)throws Exception {
        try(ParcelFileDescriptor descriptor=instrumentation.getUiAutomation().executeShellCommand(command);
            FileInputStream input=new FileInputStream(descriptor.getFileDescriptor());ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return output.toString("UTF-8");
        }
    }
}
