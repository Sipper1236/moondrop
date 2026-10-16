package xyz.moondrop.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.VpnService;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import java.util.concurrent.CountDownLatch;

/** Debug-only fixture driving the real VPN adapter with a controlled local remote. */
public final class FixtureVpnService extends VpnService {
    static volatile CountDownLatch ready=new CountDownLatch(1);
    static volatile Throwable failure;
    static void reset(){ready=new CountDownLatch(1);failure=null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String channel="moondrop_fixture";
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel,"VPN test",NotificationManager.IMPORTANCE_LOW));
        Notification notification=new Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("Moondrop routing test").build();
        if(Build.VERSION.SDK_INT>=34)startForeground(42,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(42,notification);
        new Thread(()->{
            try{NativeEngine.start(getApplicationContext(),this,intent.getStringExtra("config"));}
            catch(Throwable error){failure=error;}
            finally{ready.countDown();}
        },"fixture-vpn").start();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){NativeEngine.stop();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
