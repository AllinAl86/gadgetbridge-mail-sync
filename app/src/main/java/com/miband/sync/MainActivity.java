package com.miband.sync;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etFromEmail, etPassword;
    private TextView tvStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("mail_sync_prefs", MODE_PRIVATE);

        etFromEmail = findViewById(R.id.etFromEmail);
        etPassword = findViewById(R.id.etPassword);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnTriggerSync = findViewById(R.id.btnTriggerSync);

        etFromEmail.setText(prefs.getString("from_email", ""));
        etPassword.setText(prefs.getString("password", ""));

        btnSave.setOnClickListener(v -> {
            String from = etFromEmail.getText().toString().trim();
            String pwd = etPassword.getText().toString().trim();
            if (from.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "发件邮箱和授权码不能为空！", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("from_email", from).putString("password", pwd).apply();
            Toast.makeText(this, "配置保存成功！", Toast.LENGTH_SHORT).show();
            tvStatus.setText("配置已保存。发件账号: " + from);
        });

        btnTriggerSync.setOnClickListener(v -> {
            tvStatus.setText("正在通知 Gadgetbridge 导出数据库...");
            Intent intent = new Intent("nodomain.freeyourgadget.gadgetbridge.command.TRIGGER_EXPORT");
            sendBroadcast(intent);
            Toast.makeText(this, "已发送导出指令，等待手环数据打包...", Toast.LENGTH_SHORT).show();
        });
    }
}
