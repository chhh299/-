package com.cloudphone.auto;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements AutoAccessibilityService.LogCallback {
    private TextView tvStatus;
    private TextView tvTcpStatus;
    private TextView tvLog;
    private EditText etTargetIp;
    private EditText etTargetPort;
    private Button btnOpenSettings;
    private Button btnSaveConnect;
    private Button btnTestCmd;
    private final StringBuilder logBuilder = new StringBuilder();

    private static final String PREF_NAME = "cloud_auto_config";
    private static final String KEY_IP = "target_ip";
    private static final String KEY_PORT = "target_port";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvTcpStatus = findViewById(R.id.tv_tcp_status);
        tvLog = findViewById(R.id.tv_log);
        etTargetIp = findViewById(R.id.et_target_ip);
        etTargetPort = findViewById(R.id.et_target_port);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnSaveConnect = findViewById(R.id.btn_save_connect);
        btnTestCmd = findViewById(R.id.btn_test_cmd);

        // 加载历史配置
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedIp = sp.getString(KEY_IP, "192.168.193.51");
        int savedPort = sp.getInt(KEY_PORT, 19388);
        etTargetIp.setText(savedIp);
        etTargetPort.setText(String.valueOf(savedPort));

        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnSaveConnect.setOnClickListener(v -> {
            String ip = etTargetIp.getText().toString().trim();
            String portStr = etTargetPort.getText().toString().trim();
            if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(portStr)) {
                Toast.makeText(this, "IP 和端口不能为空！", Toast.LENGTH_SHORT).show();
                return;
            }
            int port = Integer.parseInt(portStr);
            sp.edit().putString(KEY_IP, ip).putInt(KEY_PORT, port).apply();
            Toast.makeText(this, "已保存配置！正在尝试连接对端: " + ip + ":" + port, Toast.LENGTH_SHORT).show();

            if (AutoAccessibilityService.instance != null) {
                AutoAccessibilityService.instance.reconnectClientSocket(ip, port);
            }
        });

        btnTestCmd.setOnClickListener(v -> {
            if (AutoAccessibilityService.instance != null) {
                AutoAccessibilityService.instance.handleCommand("{\"jsonrpc\":\"2.0\",\"id\":\"test\",\"method\":\"dump_ui\",\"params\":{\"__dir\":\"c2s\"}}");
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
        if (tvTcpStatus != null && message.contains("[TCP]")) {
            tvTcpStatus.setText("TCP 状态: " + message);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AutoAccessibilityService.logCallback = null;
    }
}
