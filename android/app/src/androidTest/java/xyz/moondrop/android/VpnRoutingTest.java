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
import java.util.concurrent.TimeUnit;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real TUN traffic from a different UID, decrypted by a controlled host Shadowsocks server. */
@RunWith(AndroidJUnit4.class)
public final class VpnRoutingTest {
    @Rule public ActivityTestRule<MainActivity> activity=new ActivityTestRule<>(MainActivity.class,false,false);
    @Test public void anotherApplicationRoutesThroughTheEncryptedVpn() throws Exception {
        String ssPort=InstrumentationRegistry.getArguments().getString("fixtureSsPort");
        assumeTrue("Run scripts/vpn-fixture.py and pass fixtureSsPort",ssPort!=null);
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("testing",false).commit();
        activity.launchActivity(null);
        shell(instrumentation,"appops set "+context.getPackageName()+" ACTIVATE_VPN allow");
        assertNull("VPN access must be authorized by the test harness",VpnService.prepare(context));
        Server endpoint=new Server("vpn-fixture",InstrumentationRegistry.getArguments().getString("fixtureSsHost","127.0.0.1"),Integer.parseInt(ssPort),"chacha20-ietf-poly1305","moondrop-local-routing-fixture","CA","Local fixture","",true);
        FixtureVpnService.reset();
        Intent service=new Intent(context,FixtureVpnService.class).putExtra("config",AppController.configuration(endpoint,0,true));
        try{
            context.startForegroundService(service);
            assertTrue("VPN engine did not start",FixtureVpnService.ready.await(30,TimeUnit.SECONDS));
            if(FixtureVpnService.failure!=null)throw new AssertionError("VPN engine start failed",FixtureVpnService.failure);
            // The probe Activity runs as the test APK's separate UID. Moondrop's own UID is excluded.
            // 198.18.0.42 has no host listener or direct route: only the remote fixture remaps it to HTTP.
            shell(instrumentation,"am start -W -n "+instrumentation.getContext().getPackageName()+"/xyz.moondrop.android.RoutingProbeActivity");
            String response="";long deadline=android.os.SystemClock.uptimeMillis()+12000;
            while(android.os.SystemClock.uptimeMillis()<deadline){
                response=shell(instrumentation,"run-as "+instrumentation.getContext().getPackageName()+" cat files/routing-result.txt");
                if(!response.isBlank())break;
                android.os.SystemClock.sleep(100);
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
