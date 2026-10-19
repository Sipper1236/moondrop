package xyz.moondrop.android;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import xyz.shadowmere.desktop.Server;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in fixture test: the host SS fixture forwards real HTTPS and never selects public proxies. */
@RunWith(AndroidJUnit4.class)
public final class AlwaysOnRestoreTest {
    @Test public void systemStartRestoresOnlyTheSavedPinnedRoute()throws Exception{
        ControllerTestIsolation.reset();
        String port=InstrumentationRegistry.getArguments().getString("restoreSsPort");boolean dedicated=port!=null;if(port==null)port=InstrumentationRegistry.getArguments().getString("fixtureSsPort");assumeTrue(port!=null);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("settings",0).edit().putBoolean("testing",false).apply();
        context.getSharedPreferences("moondrop-test",Context.MODE_PRIVATE).edit().putString("restore-probe-url","http://198.18.0.42/restore-probe").commit();
        Server server=new Server("manual:always-on-fixture","127.0.0.1",Integer.parseInt(port),dedicated?"aes-128-gcm":"chacha20-ietf-poly1305",dedicated?"moondrop-restore-fixture":"moondrop-local-routing-fixture","CA","","",true);
        SessionStore store=new SessionStore(context);store.save(server,AppController.configuration(server,0,true));
        try(ParcelFileDescriptor result=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("appops set --user 0 xyz.moondrop.android ACTIVATE_VPN allow")){new java.io.FileInputStream(result.getFileDescriptor()).readAllBytes();}
        assertNull("Fixture must prepare VPN permission",android.net.VpnService.prepare(context));
        AppController controller=AppController.get(context);controller.disconnect();Thread.sleep(300);
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(700);
        context.startForegroundService(new Intent(context,MoondropVpnService.class).setAction(android.net.VpnService.SERVICE_INTERFACE));
        try {long deadline=System.currentTimeMillis()+180000;while(!controller.state().connected&&controller.state().error.isEmpty()&&System.currentTimeMillis()<deadline){Thread.sleep(100);}
            assertTrue(controller.state().error,controller.state().connected);assertEquals(ServerRepository.key(server),ServerRepository.key(controller.state().connectedServer));
        }finally{controller.disconnect();Thread.sleep(300);store.erase();context.getSharedPreferences("moondrop-test",Context.MODE_PRIVATE).edit().clear().commit();try(ParcelFileDescriptor result=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("appops set --user 0 xyz.moondrop.android ACTIVATE_VPN default")){new java.io.FileInputStream(result.getFileDescriptor()).readAllBytes();}}
    }
}
