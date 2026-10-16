package xyz.moondrop.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Owns the foreground lifetime and Android's user-approved VPN permission. */
public final class MoondropVpnService extends VpnService {
    public static final String ACTION_CONNECT = "xyz.moondrop.android.CONNECT";
    public static final String ACTION_DISCONNECT = "xyz.moondrop.android.DISCONNECT";
    public static final String EXTRA_SERVER_KEY = "serverKey";
    private static final String CHANNEL = "moondrop_connection";
    private static final int NOTIFICATION_ID = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finishing;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "VPN connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows and controls the active Moondrop VPN connection");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { finishSession(); return START_NOT_STICKY; }
        if (ACTION_DISCONNECT.equals(intent.getAction())) {
            AppController.get(this).handleDisconnect(this);
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(intent.getAction())) { finishSession(); return START_NOT_STICKY; }
        finishing = false;
        Notification notification = notification("Finding your fastest connection…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
        AppController.get(this).handleConnect(this, intent.getStringExtra(EXTRA_SERVER_KEY));
        return START_NOT_STICKY;
    }

    public void updateNotification(String text) {
        main.post(() -> {
            if (!finishing) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        });
    }

    public void finishSession() {
        main.post(() -> {
            finishing = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 1,
                new Intent(this, MoondropVpnService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Moondrop").setContentText(text).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Disconnect", disconnect).build())
                .build();
    }

    @Override public void onRevoke() {
        AppController.get(this).handleDisconnect(this);
        super.onRevoke();
    }

    @Override public void onDestroy() {
        if (!finishing) AppController.get(this).handleDisconnect(this);
        super.onDestroy();
    }
}
