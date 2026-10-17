package com.cloudphone.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class AutoAccessibilityService extends AccessibilityService {
    public static final String TAG = "CloudAutoService";
    public static AutoAccessibilityService instance = null;

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastHandledCommand = "";

    public interface LogCallback {
        void onLog(String message);
    }
    public static LogCallback logCallback = null;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        log(">>> 无障碍服务已连接！开始监听 WebRTC 剪贴板信令通道...");

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
                                // 忽略自己发出的回复，避免死循环
                                if (str.contains("\"reply_for\"")) return;
                                if (!str.equals(lastHandledCommand) && str.startsWith("{") && str.endsWith("}")) {
                                    lastHandledCommand = str;
                                    handleCommand(str);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log("剪贴板读取异常: " + e.getMessage());
                    }
                }
            };
            clipboardManager.addPrimaryClipChangedListener(clipListener);
        }
    }

    /**
     * 解析并执行控制端发来的 JSON 自动化指令
     */
    public void handleCommand(String jsonStr) {
        log("收到控制指令: " + jsonStr);
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(jsonStr);
                String action = json.optString("action");
                boolean success = false;
                JSONObject reply = new JSONObject();
                reply.put("reply_for", action);

                switch (action) {
                    case "launch_app":
                        String appName = json.getString("name");
                        success = launchAppByName(appName);
                        reply.put("app", appName);
                        break;

                    case "click_text":
                        String text = json.getString("text");
                        success = clickByText(text);
                        reply.put("target", text);
                        break;

                    case "click_id":
                        String viewId = json.getString("id");
                        success = clickById(viewId);
                        reply.put("target", viewId);
                        break;

                    case "set_text":
                        String inputId = json.optString("id");
                        String val = json.getString("text");
                        success = setText(inputId, val);
                        reply.put("text", val);
                        break;

                    case "click_coord":
                        float cx = (float) json.getDouble("x");
                        float cy = (float) json.getDouble("y");
                        clickCoordinate(cx, cy);
                        success = true;
                        reply.put("x", cx);
                        reply.put("y", cy);
                        break;

                    case "swipe":
                        float x1 = (float) json.getDouble("x1");
                        float y1 = (float) json.getDouble("y1");
                        float x2 = (float) json.getDouble("x2");
                        float y2 = (float) json.getDouble("y2");
                        long duration = json.optLong("duration", 300);
                        swipe(x1, y1, x2, y2, duration);
                        success = true;
                        break;

                    case "global":
                        String key = json.getString("key");
                        success = handleGlobalAction(key);
                        reply.put("key", key);
                        break;

                    case "dump_ui":
                        JSONArray nodes = dumpCurrentUi();
                        reply.put("nodes", nodes);
                        success = true;
                        break;

                    default:
                        log("未知指令动作: " + action);
                        break;
                }

                reply.put("status", success ? "ok" : "fail");
                // 将执行结果回写进剪贴板，由 WebRTC _cloud_clipboard 自动传回 PC 控制端
                sendReply(reply.toString());
                log("指令执行完毕: " + action + " -> " + (success ? "成功" : "失败"));

            } catch (Exception e) {
                log("指令执行发生异常: " + e.getMessage());
            }
        });
    }

    /** 通过应用中文名称精准启动 App */
    private boolean launchAppByName(String appName) {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                String label = pm.getApplicationLabel(app).toString();
                if (label.contains(appName)) {
                    Intent intent = pm.getLaunchIntentForPackage(app.packageName);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        log("已启动应用: " + label + " (" + app.packageName + ")");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log("启动应用异常: " + e.getMessage());
        }
        return false;
    }

    private void sendReply(String replyText) {
        if (clipboardManager != null) {
            ClipData clip = ClipData.newPlainText("cloud_reply", replyText);
            clipboardManager.setPrimaryClip(clip);
        }
    }

    /** 按文字查找并点击（支持向父节点回溯及坐标中心点回退保底） */
    private boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        if (list != null && !list.isEmpty()) {
            for (AccessibilityNodeInfo node : list) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    /** 按控件 ID 查找并点击 */
    private boolean clickById(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
        if (list != null && !list.isEmpty()) {
            for (AccessibilityNodeInfo node : list) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    /** 递归寻找并触发点击，若节点本身不可点（如桌面文字），回退为绝对矩形中心手势模拟点击 */
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null && performClick(parent)) {
            return true;
        }
        // 关键保底兜底：取其在屏幕上的矩形中心点触发手势点击
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
        AccessibilityNodeInfo target = null;
        if (viewId != null && !viewId.isEmpty()) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list != null && !list.isEmpty()) target = list.get(0);
        }
        if (target == null) {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (target != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        return false;
    }

    /** 模拟坐标点击 */
    private void clickCoordinate(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    /** 模拟滑动 */
    private void swipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(builder.build(), null, null);
    }

    /** 全局动作 */
    private boolean handleGlobalAction(String key) {
        switch (key) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: return false;
        }
    }

    /** 抓取当前页面所有控件文本、ID 与坐标区域 */
    private JSONArray dumpCurrentUi() {
        JSONArray array = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return array;
        traverseNode(root, array);
        return array;
    }

    private void traverseNode(AccessibilityNodeInfo node, JSONArray array) {
        if (node == null) return;
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String resId = node.getViewIdResourceName();
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);

            if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0) || (resId != null)) {
                JSONObject item = new JSONObject();
                if (text != null) item.put("text", text.toString());
                if (desc != null) item.put("desc", desc.toString());
                if (resId != null) item.put("id", resId);
                item.put("clickable", node.isClickable());
                item.put("x", rect.left);
                item.put("y", rect.top);
                item.put("w", rect.width());
                item.put("h", rect.height());
                array.put(item);
            }
        } catch (Exception ignored) {}

        for (int i = 0; i < node.getChildCount(); i++) {
            traverseNode(node.getChild(i), array);
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logCallback != null) {
            mainHandler.post(() -> logCallback.onLog(msg));
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
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
