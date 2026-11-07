package com.miband.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;

/**
 * 先让手环同步，再让 Gadgetbridge 导出数据库，最后把更新后的文件发到邮箱。
 * 监听放在运行期间动态注册。安装清单里的静态接收器收不到这类自定义广播。
 */
public final class SyncJob {

    public static final String ACTION_DONE = "com.miband.sync.JOB_DONE";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_OPEN_APP = "open_app";
    private static final String TO_EMAIL = "3994199150@qq.com";
    private static final long SYNC_WAIT_MS = 90_000L;
    private static final long EXPORT_WAIT_MS = 60_000L;
    private static final long OPEN_WAIT_MS = 4_000L;
    private static final long MAX_ATTACHMENT = 45L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Object RUNNING = new Object();
    private static boolean running = false;
    private static int syncState = 0;
    private static int exportState = 0;

    private SyncJob() {
    }

    public static boolean isRunning() {
        synchronized (RUNNING) {
            return running;
        }
    }

    public static String run(Context context, boolean openApp) {
        Context app = context.getApplicationContext();
        synchronized (RUNNING) {
            if (running) {
                return "上一次同步还在进行";
            }
            running = true;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                synchronized (LOCK) {
                    if (GbClient.ACTION_SYNC_FINISH.equals(action)) {
                        syncState = 1;
                    } else if (GbClient.ACTION_EXPORT_SUCCESS.equals(action)) {
                        exportState = 1;
                    } else if (GbClient.ACTION_EXPORT_FAIL.equals(action)) {
                        exportState = -1;
                    }
                    LOCK.notifyAll();
                }
            }
        };
        try {
            register(app, receiver);
            String result;
            try {
                result = doRun(app, openApp);
            } catch (Exception e) {
                String message = e.getMessage();
                result = "同步中断：" + (message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
            }
            publish(app, result);
            return result;
        } finally {
            try {
                app.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            synchronized (RUNNING) {
                running = false;
            }
        }
    }

    private static String doRun(Context context, boolean openApp) {
        SharedPreferences prefs = context.getSharedPreferences(ExportLocator.PREFS, Context.MODE_PRIVATE);
        String from = prefs.getString("from_email", "");
        String password = prefs.getString("password", "");
        if (from == null || password == null || from.isEmpty() || password.isEmpty()) {
            return "还没保存发件邮箱和授权码";
        }
        if (!ExportLocator.hasTarget(context)) {
            return "还没选择 Gadgetbridge 导出的数据库文件";
        }
        String pkg = GbClient.installedPackage(context);
        if (pkg == null) {
            return "手机里没有安装 Gadgetbridge";
        }

        long startedAt = System.currentTimeMillis();
        ExportLocator.Meta before = ExportLocator.snapshot(context);
        synchronized (LOCK) {
            syncState = 0;
            exportState = 0;
        }

        if (openApp) {
            GbClient.openApp(context, pkg);
            if (!waitMs(OPEN_WAIT_MS)) {
                return "同步已取消";
            }
        }
        GbClient.send(context, pkg, GbClient.ACTION_SYNC);
        if (!waitUntil(SYNC_WAIT_MS, true)) {
            return "同步已取消";
        }
        GbClient.send(context, pkg, GbClient.ACTION_EXPORT);
        if (!waitUntil(EXPORT_WAIT_MS, false)) {
            return "同步已取消";
        }

        synchronized (LOCK) {
            if (exportState < 0) {
                return "Gadgetbridge 导出失败。请到「设置 → 自动化」里先选定数据库导出位置，并手动导出成功一次。";
            }
        }

        ExportLocator.Meta updated = null;
        long lookUntil = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < lookUntil) {
            updated = ExportLocator.findUpdated(context, before, startedAt);
            if (updated != null) {
                break;
            }
            if (!waitMs(2_000L)) {
                return "同步已取消";
            }
        }
        if (updated == null && exportSucceeded() && !ExportLocator.targetsTree(context)) {
            ExportLocator.Meta current = ExportLocator.snapshot(context);
            if (current != null && current.uri != null && ExportLocator.isSqlite(context, current.uri)) {
                updated = current;
            }
        }
        if (updated == null) {
            return "数据库没有更新。请确认这三件事：Gadgetbridge 正在运行；开发者选项里的 Intent API 已打开「活动同步」和「数据库导出」；这里选择的文件就是它写出的那个文件。";
        }

        File db;
        try {
            db = ExportLocator.copyToCache(context, updated.uri);
        } catch (Exception e) {
            return "读到了导出结果，但打不开数据库文件。请重新选择一次该文件。";
        }
        if (db.length() < 16) {
            return "选中的文件不是 Gadgetbridge 数据库";
        }
        if (db.length() > MAX_ATTACHMENT) {
            return "数据库超过 45MB，邮件发不出去";
        }
        String mailError = MailSender.send(from, password, TO_EMAIL, db);
        if (mailError != null) {
            return "邮件发送失败：" + mailError;
        }
        return "已把今天的手环数据库发到 " + TO_EMAIL;
    }

    private static boolean exportSucceeded() {
        synchronized (LOCK) {
            return exportState > 0;
        }
    }

    private static void register(Context context, BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(GbClient.ACTION_SYNC_FINISH);
        filter.addAction(GbClient.ACTION_EXPORT_SUCCESS);
        filter.addAction(GbClient.ACTION_EXPORT_FAIL);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** syncWait 为 true 时，同步完成通知到达就提前结束等待。 */
    private static boolean waitUntil(long timeoutMs, boolean syncWait) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (System.currentTimeMillis() < end) {
                if (syncWait && syncState > 0) {
                    return true;
                }
                if (!syncWait && exportState != 0) {
                    return true;
                }
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                try {
                    LOCK.wait(Math.min(left, 3_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean waitMs(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (System.currentTimeMillis() < end) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private static void publish(Context context, String result) {
        Intent done = new Intent(ACTION_DONE);
        done.setPackage(context.getPackageName());
        done.putExtra(EXTRA_RESULT, result);
        context.sendBroadcast(done);
    }
}
