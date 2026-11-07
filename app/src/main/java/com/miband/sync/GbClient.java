package com.miband.sync;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 向已安装的 Gadgetbridge 发送显式广播。
 * 官方接口要求带上包名；无包名的广播在 Android 8 之后不会送达。
 */
public final class GbClient {

    public static final String PACKAGE_MAIN = "nodomain.freeyourgadget.gadgetbridge";
    public static final String PACKAGE_NIGHTLY = "nodomain.freeyourgadget.gadgetbridge.nightly";

    /** 旧动作，当前版本仍会执行，老版本也认这个。 */
    public static final String ACTION_EXPORT = "nodomain.freeyourgadget.gadgetbridge.command.TRIGGER_EXPORT";
    public static final String ACTION_SYNC = "nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC";
    public static final String ACTION_SYNC_FINISH = "nodomain.freeyourgadget.gadgetbridge.action.ACTIVITY_SYNC_FINISH";
    public static final String ACTION_EXPORT_SUCCESS = "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS";
    public static final String ACTION_EXPORT_FAIL = "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_FAIL";

    private GbClient() {
    }

    public static String installedPackage(Context context) {
        PackageManager pm = context.getPackageManager();
        if (isInstalled(pm, PACKAGE_MAIN)) {
            return PACKAGE_MAIN;
        }
        if (isInstalled(pm, PACKAGE_NIGHTLY)) {
            return PACKAGE_NIGHTLY;
        }
        return null;
    }

    public static void send(Context context, String packageName, String action) {
        Intent intent = new Intent(action);
        intent.setPackage(packageName);
        context.sendBroadcast(intent);
    }

    /** 用户点同步时先把 Gadgetbridge 拉起来，它的接口接收器是进程活着才注册的。 */
    public static boolean openApp(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
