package com.miband.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 重启后恢复每天早上的同步闹钟。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SyncScheduler.schedule(context);
    }
}
