package com.miband.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** 到点后启动同步，并预约下一天。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SyncScheduler.schedule(context);
        Intent service = new Intent(context, SyncService.class);
        service.putExtra(SyncJob.EXTRA_OPEN_APP, false);
        try {
            ContextCompat.startForegroundService(context, service);
        } catch (Exception e) {
            PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    SyncJob.run(context.getApplicationContext(), false);
                } finally {
                    pending.finish();
                }
            }, "miband-sync-alarm").start();
        }
    }
}
