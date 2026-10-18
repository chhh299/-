package com.cloudphone.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class AutoAccessibilityService extends AccessibilityService {
    public static final String TAG = "CloudAutoService";
    public static AutoAccessibilityService instance = null;

    private static final String CHANNEL_ID = "cloud_auto_daemon_channel";
    private static final int NOTIFICATION_ID = 1001;

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastHandledRaw = "";

    // 实时前台状态追踪
    private String currentPackage = "";
    private String currentActivity = "";

    // 权限与通用弹窗自动放行关键词
    private static final List<String> AUTO_DISMISS_TEXTS = Arrays.asList(
            "始终允许", "仅使用中允许", "允许", "我知道了", "同意并继续", "同意", "跳过", "以后再说"
    );

    public interface LogCallback {
        void onLog(String message);
    }
    public static LogCallback logCallback = null;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        log(">>> 无障碍守护服务 v2.0 已连接！全量应用自发现与 JSON-RPC 2.0 已就绪");

        // 启动前台服务通知保活 (Foreground Service Keep-Alive)
        startForegroundKeepAlive();

        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    try {
                        if (!clipboardManager.hasPrimaryClip()) return;
                        ClipData clip = clipboardManager.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence text = clip.getItemAt(0).getText();
                            if (text != null) {
                                String str = text.toString().trim();
                                // 核心回环防守：仅受理明确携带下行签名 "__dir":"c2s" 的控制报文
                                if (!str.contains("\"__dir\":\"c2s\"") && !str.contains("\"__dir\": \"c2s\"")) {
                                    return;
                                }
                                // 防重放校验
                                if (!str.equals(lastHandledRaw) && str.startsWith("{") && str.endsWith("}")) {
                                    lastHandledRaw = str;
                                    dispatchRpcMessage(str);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log("剪贴板监听异常: " + e.getMessage());
                    }
                }
            };
            clipboardManager.addPrimaryClipChangedListener(clipListener);
        }
    }

    private void startForegroundKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "云机自动化守护服务",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("保持云手机无障碍自动化长连接常驻运行");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("云机自动化助手已常驻")
                    .setContentText("正在监听 WebRTC RPC 控制指令")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            log("启动前台通知保活异常: " + e.getMessage());
        }
    }

    /**
     * 实时监听窗口事件：感知前台 Activity，并自动清洗系统权限与营销弹窗
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null) currentPackage = pkg.toString();
            if (cls != null) currentActivity = cls.toString();

            // 自动弹窗清障
            autoDismissPopups();
        }
    }

    /**
     * 自动检测并点击系统权限弹窗或通用对话框，且确保所有节点绝对安全回收
     */
    private void autoDismissPopups() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            for (String keyword : AUTO_DISMISS_TEXTS) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(keyword);
                if (nodes != null) {
                    boolean dismissed = false;
                    for (AccessibilityNodeInfo node : nodes) {
                        if (!dismissed && performClick(node, true)) {
                            log("【自动清障】已自动放行弹窗按钮: " + keyword);
                            dismissed = true;
                        }
                        node.recycle();
                    }
                    if (dismissed) return;
                }
            }
        } catch (Exception ignored) {
        } finally {
            root.recycle();
        }
    }

    public void handleCommand(String jsonStr) {
        dispatchRpcMessage(jsonStr);
    }

    /**
     * 标准 JSON-RPC 2.0 消息路由器
     */
    private void dispatchRpcMessage(String jsonStr) {
        log("收到控制报文: " + jsonStr);
        mainHandler.post(() -> {
            String rpcId = "0";
            try {
                JSONObject json = new JSONObject(jsonStr);

                String method = json.optString("method", json.optString("action"));
                rpcId = json.optString("id", String.valueOf(System.currentTimeMillis()));
                JSONObject params = json.optJSONObject("params");
                if (params == null) params = json;

                JSONObject resultData = new JSONObject();
                JSONObject errorObj = null;

                switch (method) {
                    case "ping":
                        resultData.put("pong", true);
                        resultData.put("uptime", System.currentTimeMillis());
                        break;

                    case "list_apps":
                        resultData.put("apps", listInstalledApps());
                        break;

                    case "launch_app":
                        String appName = params.optString("name", params.optString("package"));
                        boolean launched = launchAppByName(appName);
                        if (!launched) {
                            errorObj = makeError(-32002, "APP_NOT_FOUND: " + appName);
                        } else {
                            resultData.put("launched", appName);
                        }
                        break;

                    case "get_current_activity":
                        resultData.put("package", currentPackage);
                        resultData.put("activity", currentActivity);
                        break;

                    case "click":
                    case "click_text":
                        String text = params.optString("text");
                        String id = params.optString("id");
                        boolean clicked = false;
                        if (!text.isEmpty()) {
                            clicked = clickByText(text);
                        } else if (!id.isEmpty()) {
                            clicked = clickById(id);
                        } else if (params.has("x") && params.has("y")) {
                            clickCoordinate((float) params.getDouble("x"), (float) params.getDouble("y"));
                            clicked = true;
                        }
                        if (!clicked) {
                            errorObj = makeError(-32001, "NODE_NOT_FOUND: " + (text.isEmpty() ? id : text));
                        }
                        break;

                    case "input":
                    case "set_text":
                        String inputVal = params.getString("text");
                        String targetId = params.optString("id", "");
                        boolean inputOk = setText(targetId, inputVal);
                        if (!inputOk) {
                            errorObj = makeError(-32003, "INPUT_FAILED: 目标输入框未就绪");
                        }
                        break;

                    case "swipe":
                        float x1 = (float) params.getDouble("x1");
                        float y1 = (float) params.getDouble("y1");
                        float x2 = (float) params.getDouble("x2");
                        float y2 = (float) params.getDouble("y2");
                        long dur = params.optLong("duration", 300);
                        swipe(x1, y1, x2, y2, dur);
                        break;

                    case "global":
                    case "press_key":
                        String key = params.optString("key");
                        handleGlobalAction(key);
                        break;

                    case "dump_ui":
                        resultData.put("nodes", dumpCurrentUiPruned());
                        break;

                    default:
                        errorObj = makeError(-32601, "Method not found: " + method);
                        break;
                }

                // 核心防自激回环：顶层强制注入 __dir: "s2c"，确保任何分支均具备上行签名
                JSONObject response = new JSONObject();
                response.put("jsonrpc", "2.0");
                response.put("id", rpcId);
                response.put("__dir", "s2c");

                if (errorObj != null) {
                    response.put("error", errorObj);
                } else {
                    resultData.put("status", "ok");
                    response.put("result", resultData);
                }

                sendReply(response.toString());
                log("RPC 事务交付完成: " + method + " (id=" + rpcId + ")");

            } catch (Exception e) {
                log("RPC 处理异常: " + e.getMessage());
                try {
                    JSONObject errResp = new JSONObject();
                    errResp.put("jsonrpc", "2.0");
                    errResp.put("id", rpcId);
                    errResp.put("__dir", "s2c"); // 异常捕获同样强制注入 __dir: "s2c"
                    errResp.put("error", makeError(-32603, "Internal error: " + e.getMessage()));
                    sendReply(errResp.toString());
                } catch (Exception ignored) {}
            }
        });
    }

    private JSONObject makeError(int code, String message) {
        JSONObject err = new JSONObject();
        try {
            err.put("code", code);
            err.put("message", message);
        } catch (Exception ignored) {}
        return err;
    }

    private void sendReply(String replyText) {
        if (clipboardManager != null) {
            ClipData clip = ClipData.newPlainText("cloud_rpc_reply", replyText);
            clipboardManager.setPrimaryClip(clip);
        }
    }

    /**
     * 全量自发现并导出系统中所有安装的 Launchable 应用
     */
    private JSONArray listInstalledApps() {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

            for (ResolveInfo ri : resolveInfos) {
                if (ri.activityInfo == null) continue;
                JSONObject item = new JSONObject();
                String label = ri.loadLabel(pm).toString();
                String pkg = ri.activityInfo.packageName;
                String act = ri.activityInfo.name;

                item.put("name", label);
                item.put("package", pkg);
                item.put("activity", act);
                arr.put(item);
            }
        } catch (Exception e) {
            log("listInstalledApps 异常: " + e.getMessage());
        }
        return arr;
    }

    /**
     * 显式 Intent 跨进程直接拉起应用（按中文名或包名）
     */
    private boolean launchAppByName(String identifier) {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(identifier);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                log("显式包名拉起应用: " + identifier);
                return true;
            }

            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                String label = pm.getApplicationLabel(app).toString();
                if (label.contains(identifier)) {
                    intent = pm.getLaunchIntentForPackage(app.packageName);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        log("中文标签匹配拉起应用: " + label + " (" + app.packageName + ")");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log("启动应用异常: " + e.getMessage());
        }
        return false;
    }

    /** 按文字查找并点击，且安全回收列表全部节点 */
    private boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean clicked = false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
            if (list != null) {
                for (AccessibilityNodeInfo node : list) {
                    if (!clicked && performClick(node, true)) {
                        clicked = true;
                    }
                    node.recycle(); // 确保遍历完列表中的每个节点都显式 recycle
                }
            }
        } finally {
            root.recycle();
        }
        return clicked;
    }

    /** 按控件 ID 查找并点击，且安全回收列表全部节点 */
    private boolean clickById(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean clicked = false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list != null) {
                for (AccessibilityNodeInfo node : list) {
                    if (!clicked && performClick(node, true)) {
                        clicked = true;
                    }
                    node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
        return clicked;
    }

    /**
     * 三级级联降级点击保底：限制父节点最多回溯一层，未命中直接点击当前目标矩形中心
     */
    private boolean performClick(AccessibilityNodeInfo node, boolean allowParent) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (allowParent) {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                try {
                    if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                } finally {
                    parent.recycle();
                }
            }
        }
        // 关键兜底：获取当前目标节点自身的屏幕物理外接矩形中心，发送原生触摸手势
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.height() > 0) {
            clickCoordinate(rect.centerX(), rect.centerY());
            return true;
        }
        return false;
    }

    /** 直接设置文本内容（免弹软键盘） */
    private boolean setText(String viewId, String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo target = null;
            if (viewId != null && !viewId.isEmpty()) {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
                if (list != null) {
                    for (AccessibilityNodeInfo n : list) {
                        if (target == null) target = n;
                        else n.recycle();
                    }
                }
            }
            if (target == null) {
                target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (target != null) {
                try {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                } finally {
                    target.recycle();
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    /** 模拟坐标点击 (100ms 接触时长) */
    private void clickCoordinate(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    /** 模拟平滑滑动 */
    private void swipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(builder.build(), null, null);
    }

    /** 全局物理按键 */
    private boolean handleGlobalAction(String key) {
        if (key == null) return false;
        switch (key) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: return false;
        }
    }

    /**
     * UI 树智能剪枝抓取算法：限制深度 <= 12，严格 recycle 回收 Binder 句柄，体积严格 <= 5KB (4500 字节安全预算)
     */
    private JSONArray dumpCurrentUiPruned() {
        JSONArray array = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return array;
        try {
            int[] currentBytes = new int[]{2}; // 初始计入 "[]" 2 字节
            traverseAndPrune(root, array, 0, currentBytes);
        } finally {
            root.recycle();
        }
        return array;
    }

    private void traverseAndPrune(AccessibilityNodeInfo node, JSONArray array, int depth, int[] currentBytes) {
        if (node == null || depth > 12 || array.length() >= 60 || currentBytes[0] >= 4200) return;
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String resId = node.getViewIdResourceName();
            boolean clickable = node.isClickable();

            if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0) || clickable || resId != null) {
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                if (rect.width() > 0 && rect.height() > 0) {
                    JSONObject item = new JSONObject();
                    if (text != null && text.length() > 0) item.put("text", text.toString().trim());
                    if (desc != null && desc.length() > 0) item.put("desc", desc.toString().trim());
                    if (resId != null) item.put("id", resId);
                    item.put("clickable", clickable);
                    item.put("x", rect.left);
                    item.put("y", rect.top);
                    item.put("w", rect.width());
                    item.put("h", rect.height());

                    int itemLen = item.toString().getBytes("UTF-8").length + 1;
                    if (currentBytes[0] + itemLen <= 4500) {
                        array.put(item);
                        currentBytes[0] += itemLen;
                    } else {
                        return;
                    }
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                if (currentBytes[0] >= 4200) break;
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        traverseAndPrune(child, array, depth + 1, currentBytes);
                    } finally {
                        child.recycle();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logCallback != null) {
            mainHandler.post(() -> logCallback.onLog(msg));
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (clipboardManager != null && clipListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
    }
}
