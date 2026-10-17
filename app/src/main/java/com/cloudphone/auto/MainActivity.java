package com.cloudphone.auto;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements AutoAccessibilityService.LogCallback {
    private TextView tvStatus;
    private TextView tvLog;
    private Button btnOpenSettings;
    private Button btnTestCmd;
    private final StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnTestCmd = findViewById(R.id.btn_test_cmd);

        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnTestCmd.setOnClickListener(v -> {
            if (AutoAccessibilityService.instance != null) {
                AutoAccessibilityService.instance.handleCommand("{\"action\":\"dump_ui\"}");
                Toast.makeText(this, "已触发 UI 布局抓取测试", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "请先在系统设置中开启本无障碍服务！", Toast.LENGTH_LONG).show();
            }
        });

        AutoAccessibilityService.logCallback = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean enabled = isAccessibilitySettingsOn(this);
        if (enabled) {
            tvStatus.setText(R.string.service_status_enabled);
            tvStatus.setBackgroundColor(Color.parseColor("#4CAF50"));
        } else {
            tvStatus.setText(R.string.service_status_disabled);
            tvStatus.setBackgroundColor(Color.parseColor("#F44336"));
        }
    }

    private boolean isAccessibilitySettingsOn(Context context) {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + AutoAccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getApplicationContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                colonSplitter.setString(settingValue);
                while (colonSplitter.hasNext()) {
                    String accessibilityService = colonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void onLog(String message) {
        logBuilder.append(message).append("\n");
        if (tvLog != null) {
            tvLog.setText(logBuilder.toString());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AutoAccessibilityService.logCallback = null;
    }
}
