package com.cloudphone.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

import java.util.Arrays;
import java.util.List;

public class AutoAccessibilityService extends AccessibilityService {
    public static final String TAG = "CloudAutoService";
    public static AutoAccessibilityService instance = null;

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
                                // 1. 回环防御：若为自身发出的 s2c 响应，直接丢弃
                                if (str.contains("\"__dir\":\"s2c\"") || str.contains("\"reply_for\"")) return;
                                // 2. 防重放校验
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
     * 自动检测并点击系统权限弹窗或通用对话框
     */
    private void autoDismissPopups() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            for (String keyword : AUTO_DISMISS_TEXTS) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(keyword);
                if (nodes != null && !nodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : nodes) {
                        try {
                            if (performClick(node)) {
                                log("【自动清障】已自动放行弹窗按钮: " + keyword);
                                return;
                            }
                        } finally {
                            node.recycle();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            root.recycle();
        }
    }

    /**
     * 外部/测试触发接口
     */
    public void handleCommand(String jsonStr) {
        dispatchRpcMessage(jsonStr);
    }

    /**
     * 标准 JSON-RPC 2.0 消息路由器
     */
    public void dispatchRpcMessage(String jsonStr) {
        log("收到控制报文: " + jsonStr);
        mainHandler.post(() -> {
            String rpcId = "0";
            try {
                JSONObject json = new JSONObject(jsonStr);

                // 兼容标准 JSON-RPC 2.0 与旧版格式
                String method = json.optString("method", json.optString("action"));
                rpcId = json.optString("id", String.valueOf(System.currentTimeMillis()));
                JSONObject params = json.optJSONObject("params");
                if (params == null) params = json; // 平铺参数兼容

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

                // 组装标准 JSON-RPC 2.0 响应 (携带 __dir: "s2c")
                JSONObject response = new JSONObject();
                response.put("jsonrpc", "2.0");
                response.put("id", rpcId);
                resultData.put("__dir", "s2c");
                resultData.put("status", errorObj == null ? "ok" : "fail");

                if (errorObj != null) {
                    response.put("error", errorObj);
                } else {
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
            // 1. 若直接是合法包名，尝试直连
            Intent intent = pm.getLaunchIntentForPackage(identifier);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                log("显式包名拉起应用: " + identifier);
                return true;
            }

            // 2. 遍历查找匹配中文名称
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

    /** 按文字查找并点击（支持向父节点回溯及坐标中心点回退保底） */
    private boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo node : list) {
                    try {
                        if (performClick(node)) return true;
                    } finally {
                        node.recycle();
                    }
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    /** 按控件 ID 查找并点击 */
    private boolean clickById(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo node : list) {
                    try {
                        if (performClick(node)) return true;
                    } finally {
                        node.recycle();
                    }
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    /**
     * 三级级联降级点击保底
     */
    private boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            try {
                if (performClick(parent)) return true;
            } finally {
                parent.recycle();
            }
        }
        // 关键兜底：获取节点物理外接矩形中心，发送原生触摸手势
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
                if (list != null && !list.isEmpty()) target = list.get(0);
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
     * UI 树智能剪枝抓取算法：限制深度 <= 12，严格 recycle 回收 Binder 句柄，体积 <= 5KB
     */
    private JSONArray dumpCurrentUiPruned() {
        JSONArray array = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return array;
        try {
            traverseAndPrune(root, array, 0);
        } finally {
            root.recycle();
        }
        return array;
    }

    private void traverseAndPrune(AccessibilityNodeInfo node, JSONArray array, int depth) {
        if (node == null || depth > 12 || array.length() >= 80) return;
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String resId = node.getViewIdResourceName();
            boolean clickable = node.isClickable();

            // 智能剪枝：仅保留具备有效交互特征的节点
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
                    array.put(item);
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        traverseAndPrune(child, array, depth + 1);
                    } finally {
                        child.recycle(); // 强制显式释放 Binder 代理句柄，杜绝死锁假死
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
