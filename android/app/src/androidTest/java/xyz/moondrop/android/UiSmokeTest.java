package xyz.moondrop.android;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.os.Build;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import xyz.shadowmere.desktop.Server;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Exercises a cached first launch without starting a VPN or testing public servers. */
@RunWith(AndroidJUnit4.class)
public final class UiSmokeTest {
    @Rule public ActivityTestRule<MainActivity> activityRule=new ActivityTestRule<>(MainActivity.class,false,false);

    @Test public void cachedServersAndAdvancedSettingsAreVisible() throws Exception {
        ControllerTestIsolation.reset();
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        // Exercise the promised offline cache path rather than race a very fast live feed refresh.
        shell("svc wifi disable");shell("svc data disable");
        assertTrue(context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
                .putBoolean("testing",false).putBoolean("advanced",false)
                .putStringSet("protocols",Preferences.PROTOCOLS).commit());
        Server cached=new Server("ui-fixture","8.8.8.8",443,"aes-128-gcm","test-fixture-only","CA","","",true);
        Measurement measured=new Measurement(42,System.currentTimeMillis()-120_000,2_000_000,3,3);
        new ServerRepository(context).save(List.of(cached),Map.of(ServerRepository.key(cached),measured));
        MainActivity activity=activityRule.launchActivity(null);
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();
            assertNotNull(findText(root,"MOONDROP"));assertNotNull(findText(root,"Connect"));
            assertNotNull(findText(root,"Shadowsocks"));assertNotNull(findTextContaining(root,"42 ms"));
            assertNotNull(findTextContaining(root,"2m ago"));
            assertFalse(AppController.get(context).preferences().automaticTesting);
            if(Build.VERSION.SDK_INT>=30){
                View content=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                WindowInsets synthetic=new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),Insets.of(3,24,5,16))
                        .setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,160)).setVisible(WindowInsets.Type.systemBars()|WindowInsets.Type.ime(),true).build();
                content.dispatchApplyWindowInsets(synthetic);
                float density=activity.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(20*density)+3,content.getPaddingLeft());
                assertEquals(Math.round(12*density)+24,content.getPaddingTop());
                assertEquals(Math.round(20*density)+5,content.getPaddingRight());
                assertEquals(160,content.getPaddingBottom());content.requestApplyInsets();
            }
            View settings=findDescription(root,"Settings");assertNotNull(settings);settings.performClick();
        });
        instrumentation.waitForIdleSync();
        // Inspect our dialog directly so unrelated system permission/ANR windows cannot steal
        // the global accessibility root while exercising the settings controls.
        instrumentation.runOnMainSync(()->{try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("settingsDialog");field.setAccessible(true);
            android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(activity);assertNotNull(dialog);
            View root=dialog.getWindow().getDecorView();View advanced=findDescription(root,"Advanced mode");assertNotNull(advanced);assertTrue(advanced.isClickable());advanced.performClick();
            assertNotNull(findText(root,"Protocols"));assertNotNull(findText(root,"Copy diagnostics"));
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
        assertTrue(AppController.get(context).preferences().advancedMode);
        assertFalse(AppController.get(context).preferences().automaticTesting);
    }

    @org.junit.After public void restoreConnectivity()throws Exception{shell("svc wifi enable");shell("svc data enable");}
    private static void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor result=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)){new java.io.FileInputStream(result.getFileDescriptor()).readAllBytes();}}
    private static View findText(View root,String text){return find(root,v->v instanceof TextView&&text.contentEquals(((TextView)v).getText()));}
    private static View findTextContaining(View root,String text){return find(root,v->v instanceof TextView&&((TextView)v).getText().toString().contains(text));}
    private static View findDescription(View root,String text){return find(root,v->text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));}
    private interface Matches {boolean test(View view);}
    private static View find(View root,Matches matches){
        if(matches.test(root))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),matches);if(result!=null)return result;}}
        return null;
    }
}
