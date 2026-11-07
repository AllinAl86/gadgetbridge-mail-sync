package com.miband.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/** 把下一次自动同步订到用户选的钟点。 */
public final class SyncScheduler {

    static final String KEY_DAILY = "daily_enabled";
    static final String KEY_HOUR = "daily_hour";
    static final String KEY_MINUTE = "daily_minute";
    private static final int REQUEST = 7101;

    private SyncScheduler() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ExportLocator.PREFS, Context.MODE_PRIVATE);
    }

    static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = pending(context);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(pending);
        if (!prefs(context).getBoolean(KEY_DAILY, false)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return;
        }
        long triggerAt = nextTrigger(context);
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } catch (SecurityException ignored) {
        }
    }

    static long nextTrigger(Context context) {
        SharedPreferences prefs = prefs(context);
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, prefs.getInt(KEY_HOUR, 7));
        calendar.set(Calendar.MINUTE, prefs.getInt(KEY_MINUTE, 30));
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    static String nextText(Context context) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nextTrigger(context));
        return String.format(LocaleHolder.LOCALE, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST, intent, flags);
    }

    /** 避免在字段初始化里引用 Locale 时拉长主类。 */
    private static final class LocaleHolder {
        static final java.util.Locale LOCALE = java.util.Locale.US;
    }
}
