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
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        assertTrue(context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
                .putBoolean("testing",false).putBoolean("advanced",false)
                .putStringSet("protocols",Preferences.PROTOCOLS).commit());
        Server cached=new Server("ui-fixture","192.0.2.17",443,"aes-128-gcm","test-fixture-only","CA","","",true);
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
        AccessibilityNodeInfo advanced=awaitAccessibility("Advanced mode",true);
        assertTrue("Advanced mode must be an accessible switch",advanced.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
        awaitAccessibility("Protocols",false);
        awaitAccessibility("Copy diagnostics",false);
        assertTrue(AppController.get(context).preferences().advancedMode);
        assertFalse(AppController.get(context).preferences().automaticTesting);
    }

    private static View findText(View root,String text){return find(root,v->v instanceof TextView&&text.contentEquals(((TextView)v).getText()));}
    private static View findTextContaining(View root,String text){return find(root,v->v instanceof TextView&&((TextView)v).getText().toString().contains(text));}
    private static View findDescription(View root,String text){return find(root,v->text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));}
    private interface Matches {boolean test(View view);}
    private static View find(View root,Matches matches){
        if(matches.test(root))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),matches);if(result!=null)return result;}}
        return null;
    }
    private static AccessibilityNodeInfo awaitAccessibility(String text,boolean clickable){
        long deadline=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<deadline){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();AccessibilityNodeInfo match=findNode(root,text,clickable);if(match!=null)return match;SystemClock.sleep(50);}
        throw new AssertionError("Missing settings control: "+text);
    }
    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,String text,boolean clickable){
        if(node==null)return null;
        boolean matches=text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription());
        if(matches&&(!clickable||node.isClickable()))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=findNode(node.getChild(i),text,clickable);if(found!=null)return found;}
        return null;
    }
}
