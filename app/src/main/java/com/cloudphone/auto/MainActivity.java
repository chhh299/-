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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

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

    private Thread clientThread = null;
    private volatile boolean isRunning = false;

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

        // 加载历史配置 (默认对准 OpenWrt 192.168.193.246:19389)
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedIp = sp.getString(KEY_IP, "192.168.193.246");
        int savedPort = sp.getInt(KEY_PORT, 19389);
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
            Toast.makeText(this, "正在建立连接至: " + ip + ":" + port, Toast.LENGTH_SHORT).show();

            // 无论无障碍服务是否启动，前台立刻启动长连接线程连接对端 (OpenWrt / PC)
            startConnection(ip, port);
        });

        btnTestCmd.setOnClickListener(v -> {
            if (AutoAccessibilityService.instance != null) {
                AutoAccessibilityService.instance.handleCommand("{\"jsonrpc\":\"2.0\",\"id\":\"test\",\"method\":\"dump_ui\",\"params\":{\"__dir\":\"c2s\"}}");
                Toast.makeText(this, "已触发 UI 抓取测试", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "请在系统设置中先开启无障碍服务！", Toast.LENGTH_LONG).show();
            }
        });

        AutoAccessibilityService.logCallback = this;

        // 打开 App 自动根据已存配置尝试连接对端
        startConnection(savedIp, savedPort);
    }

    private synchronized void startConnection(String ip, int port) {
        if (clientThread != null) {
            isRunning = false;
            clientThread.interrupt();
            clientThread = null;
        }

        isRunning = true;
        clientThread = new Thread(() -> {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                Socket socket = null;
                try {
                    appendLog("[TCP] 正在连接对端: " + ip + ":" + port + "...");
                    socket = new Socket(ip, port);
                    appendLog("[TCP] ★ 成功连上对端！(" + ip + ":" + port + ")");
                    updateTcpStatus(true, ip + ":" + port);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

                    while (isRunning && !socket.isClosed()) {
                        String line = reader.readLine();
                        if (line == null) {
                            appendLog("[TCP] 对端已断开连接");
                            break;
                        }
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            appendLog("[收到指令] " + trimmed);
                            String resp = "";
                            if (AutoAccessibilityService.instance != null) {
                                resp = AutoAccessibilityService.instance.executeRpcSync(trimmed);
                            } else {
                                resp = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"ACCESSIBILITY_SERVICE_NOT_ENABLED\"}}";
                            }
                            writer.write(resp + "\n");
                            writer.flush();
                            appendLog("[发送应答] 交付完成");
                        }
                    }
                } catch (Exception e) {
                    appendLog("[TCP 连接异常/等待重连]: " + e.getMessage());
                    updateTcpStatus(false, e.getMessage());
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                }

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        clientThread.start();
    }

    private void updateTcpStatus(boolean connected, String detail) {
        runOnUiThread(() -> {
            if (tvTcpStatus != null) {
                if (connected) {
                    tvTcpStatus.setText("TCP 状态: 已连接 (" + detail + ")");
                    tvTcpStatus.setBackgroundColor(Color.parseColor("#C8E6C9"));
                } else {
                    tvTcpStatus.setText("TCP 状态: 未连接 (" + detail + ")");
                    tvTcpStatus.setBackgroundColor(Color.parseColor("#FFCDD2"));
                }
            }
        });
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            logBuilder.append(msg).append("\n");
            if (tvLog != null) {
                tvLog.setText(logBuilder.toString());
            }
        });
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
        appendLog(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (clientThread != null) {
            clientThread.interrupt();
        }
        AutoAccessibilityService.logCallback = null;
    }
}
