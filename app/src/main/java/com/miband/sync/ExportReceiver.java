package com.miband.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExportReceiver extends BroadcastReceiver {

    private static final String TO_EMAIL = "3994199150@qq.com";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS".equals(action)) {
            Toast.makeText(context, "收到手环数据导出完成广播，正在发送邮件...", Toast.LENGTH_LONG).show();

            SharedPreferences prefs = context.getSharedPreferences("mail_sync_prefs", Context.MODE_PRIVATE);
            String from = prefs.getString("from_email", "");
            String pwd = prefs.getString("password", "");

            if (from.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(context, "未配置发件邮箱，取消自动发送！", Toast.LENGTH_SHORT).show();
                return;
            }

            // 寻找最新的 Gadgetbridge.sqlite 导出文件
            File latestDb = findLatestExportFile();
            if (latestDb == null) {
                Toast.makeText(context, "未找到导出的手环数据库文件！", Toast.LENGTH_SHORT).show();
                return;
            }

            new Thread(() -> {
                boolean ok = MailSender.sendMailWithAttachment(from, pwd, TO_EMAIL, latestDb);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (ok) {
                        Toast.makeText(context, "【成功】手环数据已发送至电脑 Agent 邮箱！", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(context, "【失败】邮件发送失败，请检查网络或授权码！", Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        }
    }

    private File findLatestExportFile() {
        List<File> candidates = new ArrayList<>();
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File rootDir = Environment.getExternalStorageDirectory();

        scanDir(downloadDir, candidates);
        scanDir(new File(rootDir, "Gadgetbridge"), candidates);

        File latest = null;
        for (File f : candidates) {
            if (latest == null || f.lastModified() > latest.lastModified()) {
                latest = f;
            }
        }
        return latest;
    }

    private void scanDir(File dir, List<File> list) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (f.getName().toLowerCase().endsWith(".sqlite") || f.getName().toLowerCase().endsWith(".db"))) {
                        list.add(f);
                    }
                }
            }
        }
    }
}
