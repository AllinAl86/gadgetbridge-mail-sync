package com.miband.sync;

import android.Manifest;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private EditText etFromEmail;
    private EditText etPassword;
    private TextView tvFile;
    private TextView tvStatus;
    private SwitchCompat switchDaily;
    private Button btnPickTime;
    private SharedPreferences prefs;
    private boolean suppressSwitch;

    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> saveUri(uri, false));
    private final ActivityResultLauncher<Uri> pickFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> saveUri(uri, true));

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String result = intent.getStringExtra(SyncJob.EXTRA_RESULT);
            if (result != null) {
                tvStatus.setText(result);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(ExportLocator.PREFS, MODE_PRIVATE);

        etFromEmail = findViewById(R.id.etFromEmail);
        etPassword = findViewById(R.id.etPassword);
        tvFile = findViewById(R.id.tvFile);
        tvStatus = findViewById(R.id.tvStatus);
        switchDaily = findViewById(R.id.switchDaily);
        btnPickTime = findViewById(R.id.btnPickTime);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnPickFile = findViewById(R.id.btnPickFile);
        Button btnPickFolder = findViewById(R.id.btnPickFolder);
        Button btnTriggerSync = findViewById(R.id.btnTriggerSync);

        etFromEmail.setText(prefs.getString("from_email", ""));
        etPassword.setText(prefs.getString("password", ""));
        refreshFileLabel();
        refreshTimeLabel();

        btnSave.setOnClickListener(v -> saveAccount());
        btnPickFile.setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        btnPickFolder.setOnClickListener(v -> pickFolder.launch(null));
        btnPickTime.setOnClickListener(v -> pickTime());
        btnTriggerSync.setOnClickListener(v -> startSync());

        suppressSwitch = true;
        switchDaily.setChecked(prefs.getBoolean(SyncScheduler.KEY_DAILY, false));
        suppressSwitch = false;
        switchDaily.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitch) {
                return;
            }
            prefs.edit().putBoolean(SyncScheduler.KEY_DAILY, checked).apply();
            if (checked) {
                requestExactAlarm();
                SyncScheduler.schedule(this);
                tvStatus.setText("已预约每天 " + SyncScheduler.nextText(this) + " 自动同步");
            } else {
                SyncScheduler.schedule(this);
                tvStatus.setText("已关闭每天自动同步");
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(SyncJob.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
        SyncScheduler.schedule(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(resultReceiver);
        } catch (Exception ignored) {
        }
    }

    private boolean saveAccount() {
        String from = etFromEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        if (from.isEmpty() || password.isEmpty() || !from.contains("@")) {
            Toast.makeText(this, "请填写发件邮箱和授权码", Toast.LENGTH_SHORT).show();
            return false;
        }
        prefs.edit().putString("from_email", from).putString("password", password).apply();
        tvStatus.setText("发件账号已保存");
        return true;
    }

    private void saveUri(Uri uri, boolean tree) {
        if (uri == null) {
            return;
        }
        boolean persisted = ExportLocator.save(this, uri, tree);
        refreshFileLabel();
        if (persisted) {
            tvStatus.setText(tree ? "已授权这个文件夹" : "已授权这个数据库文件");
        } else {
            tvStatus.setText("这次能读，但系统没给长期授权，下次打开请再选一次");
        }
    }

    private void pickTime() {
        int hour = prefs.getInt(SyncScheduler.KEY_HOUR, 7);
        int minute = prefs.getInt(SyncScheduler.KEY_MINUTE, 30);
        new TimePickerDialog(this, (view, h, m) -> {
            prefs.edit().putInt(SyncScheduler.KEY_HOUR, h).putInt(SyncScheduler.KEY_MINUTE, m).apply();
            refreshTimeLabel();
            if (switchDaily.isChecked()) {
                SyncScheduler.schedule(this);
                tvStatus.setText("已改为每天 " + SyncScheduler.nextText(this) + " 自动同步");
            }
        }, hour, minute, true).show();
    }

    private void startSync() {
        if (!ExportLocator.hasTarget(this)) {
            tvStatus.setText("先选择 Gadgetbridge 导出的数据库文件");
            return;
        }
        if (GbClient.installedPackage(this) == null) {
            tvStatus.setText("请先安装 Gadgetbridge 官方版");
            return;
        }
        if (!saveAccount()) {
            return;
        }
        String pkg = GbClient.installedPackage(this);
        GbClient.openApp(this, pkg);
        tvStatus.setText("正在打开 Gadgetbridge，同步后发送邮件");
        Intent service = new Intent(this, SyncService.class);
        service.putExtra(SyncJob.EXTRA_OPEN_APP, true);
        ContextCompat.startForegroundService(this, service);
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void refreshFileLabel() {
        String name = ExportLocator.savedName(this);
        tvFile.setText(name == null || name.isEmpty() ? "尚未选择" : name);
    }

    private void refreshTimeLabel() {
        int hour = prefs.getInt(SyncScheduler.KEY_HOUR, 7);
        int minute = prefs.getInt(SyncScheduler.KEY_MINUTE, 30);
        btnPickTime.setText(String.format(java.util.Locale.US, "自动发送时间 %02d:%02d", hour, minute));
    }
}
