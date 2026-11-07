package com.miband.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** 同步可能要一两分钟，用前台服务保住进程，避免发信做到一半被系统杀掉。 */
public class SyncService extends Service {

    static final String CHANNEL_ID = "miband_sync";
    private static final int ONGOING_ID = 7102;
    static final int RESULT_ID = 7103;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification ongoing = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("手环邮件同步")
                .setContentText("正在同步手环并发送数据库")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ONGOING_ID, ongoing);
        }

        boolean openApp = intent != null && intent.getBooleanExtra(SyncJob.EXTRA_OPEN_APP, false);
        new Thread(() -> {
            String result;
            try {
                result = SyncJob.run(getApplicationContext(), openApp);
            } catch (Exception e) {
                String message = e.getMessage();
                result = "同步中断：" + (message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
            }
            showResult(result);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(startId);
        }, "miband-sync").start();
        return START_NOT_STICKY;
    }

    private void showResult(String result) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification done = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("手环邮件同步")
                .setContentText(result)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(result))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(RESULT_ID, done);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "手环同步", NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
