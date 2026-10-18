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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AutoAccessibilityService extends AccessibilityService {
    public static final String TAG = "CloudAutoService";
    public static AutoAccessibilityService instance = null;

    // 避开 5555, 8080, 8000，使用专属局域网 TCP RPC 端口
    public static final int TCP_RPC_PORT = 19388;

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastHandledRaw = "";

    private ServerSocket rpcServerSocket = null;
    private Thread rpcServerThread = null;

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
        log(">>> 无障碍守护服务 v3.0 已启动！同时监听 TCP:19388 与 剪贴板通道");

        // 1. 启动局域网 TCP RPC 服务器 (端口 19388)
        startTcpRpcServer();

        // 2. 剪贴板监听备用通道
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
                                if (!str.contains("\"__dir\":\"c2s\"") && !str.contains("\"__dir\": \"c2s\"")) return;
                                if (!str.equals(lastHandledRaw) && str.startsWith("{") && str.endsWith("}")) {
                                    lastHandledRaw = str;
                                    dispatchRpcMessageAsync(str, null);
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
     * 启动基于 TCP 19388 端口的原生局域网长连接服务
     * 彻底突破任何 Android 剪贴板后台限制与 WebRTC 隔离！
     */
    private void startTcpRpcServer() {
        if (rpcServerThread != null) return;
        rpcServerThread = new Thread(() -> {
            try {
                rpcServerSocket = new ServerSocket(TCP_RPC_PORT);
                log(">>> [TCP Server] 已成功绑定并监听端口: " + TCP_RPC_PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = rpcServerSocket.accept();
                    handleIncomingSocket(socket);
                }
            } catch (Exception e) {
                log("[TCP Server] 监听异常: " + e.getMessage());
            }
        });
        rpcServerThread.start();
    }

    private void handleIncomingSocket(Socket socket) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

                String reqLine = reader.readLine();
                if (reqLine != null && !reqLine.trim().isEmpty()) {
                    log("[TCP Recv] " + reqLine);
                    String respLine = executeRpcSync(reqLine.trim());
                    writer.write(respLine + "\n");
                    writer.flush();
                    log("[TCP Sent] 响应回写完毕");
                }
            } catch (Exception e) {
                log("[TCP Handler] 客户端交互异常: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 同步执行 RPC 指令并返回响应 JSON
     */
    public String executeRpcSync(String jsonStr) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> responseRef = new AtomicReference<>("");

        dispatchRpcMessageAsync(jsonStr, response -> {
            responseRef.set(response);
            latch.countDown();
        });

        try {
            boolean done = latch.await(15, TimeUnit.SECONDS);
            if (!done) {
                JSONObject timeoutResp = new JSONObject();
                timeoutResp.put("jsonrpc", "2.0");
                timeoutResp.put("error", makeError(-32000, "EXECUTION_TIMEOUT"));
                return timeoutResp.toString();
            }
        } catch (InterruptedException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"INTERRUPTED\"}}";
        }

        return responseRef.get();
    }

    public interface ResponseCallback {
        void onResponse(String jsonResponse);
    }

    /**
     * 标准 JSON-RPC 2.0 异步消息路由分发
     */
    public void dispatchRpcMessageAsync(String jsonStr, ResponseCallback callback) {
        log("处理指令: " + jsonStr);
        mainHandler.post(() -> {
            String rpcId = "0";
            try {
                JSONObject json = new JSONObject(jsonStr);

                String method = json.optString("method", json.optString("action"));
                rpcId = json.optString("id", String.valueOf(System.currentTimeMillis()));
                final String finalRpcId = rpcId;
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

                    case "teld_punch_workflow":
                    case "teld_punch":
                        new Thread(() -> executeTeldPunchWorkflow(finalRpcId, callback)).start();
                        return;

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

                String respStr = response.toString();
                if (callback != null) {
                    callback.onResponse(respStr);
                } else {
                    sendReply(respStr);
                }

            } catch (Exception e) {
                log("RPC 处理异常: " + e.getMessage());
                try {
                    JSONObject errResp = new JSONObject();
                    errResp.put("jsonrpc", "2.0");
                    errResp.put("id", rpcId);
                    errResp.put("__dir", "s2c");
                    errResp.put("error", makeError(-32603, "Internal error: " + e.getMessage()));
                    String errStr = errResp.toString();
                    if (callback != null) {
                        callback.onResponse(errStr);
                    } else {
                        sendReply(errStr);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 端侧自主闭环打卡流水线
     */
    private void executeTeldPunchWorkflow(String rpcId, ResponseCallback callback) {
        log(">>> [Workflow] 开始执行特来电打卡工作流...");
        boolean success = false;
        String finalStatus = "FAILED";

        try {
            // 1. 先发 Home 回桌面，再拉起特来电 (防止后台 Activity 拦截)
            handleGlobalAction("home");
            Thread.sleep(1000);
            launchAppByName("com.qdtevc.teld.app");
            Thread.sleep(4000);

            // 2. 弹窗清洗 (最多重试 5 次)
            for (int i = 0; i < 5; i++) {
                if (clickByText("跳过") || clickByText("跳过广告")) {
                    log("[Workflow] 跳过开屏广告");
                    Thread.sleep(1200);
                }
                if (clickById("com.qdtevc.teld.app:id/iv_close") || clickByText("关闭")) {
                    log("[Workflow] 点击关闭弹窗");
                    Thread.sleep(1200);
                }

                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("打卡领好礼");
                        if (nodes != null && !nodes.isEmpty()) {
                            for (AccessibilityNodeInfo n : nodes) n.recycle();
                            log("[Workflow] 检测到【打卡领好礼】入口，首页已就绪！");
                            break;
                        }
                    } finally {
                        root.recycle();
                    }
                }
                Thread.sleep(800);
            }

            // 3. 点击【打卡领好礼】(语义优先)
            log("[Workflow] 点击【打卡领好礼】入口...");
            clickByText("打卡领好礼");
            Thread.sleep(4500);

            // 4. 寻找并点击核心打卡按钮【点击完成今日打卡】
            log("[Workflow] 点击【点击完成今日打卡】...");
            for (int i = 0; i < 6; i++) {
                if (clickByText("点击完成今日打卡") || clickByText("完成今日打卡") || clickByText("今日打卡")) {
                    log("[Workflow] 成功点击打卡按钮！");
                    break;
                }
                Thread.sleep(1000);
            }
            Thread.sleep(2000);

            // 5. 状态断言
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    List<AccessibilityNodeInfo> doneNodes = root.findAccessibilityNodeInfosByText("今日打卡已完成");
                    if (doneNodes != null && !doneNodes.isEmpty()) {
                        finalStatus = "ALREADY_PUNCHED (今日打卡已完成)";
                        success = true;
                        for (AccessibilityNodeInfo n : doneNodes) n.recycle();
                    } else {
                        finalStatus = "PUNCH_SUBMITTED (已提交打卡)";
                        success = true;
                    }
                } finally {
                    root.recycle();
                }
            } else {
                finalStatus = "COMPLETED_WITHOUT_ROOT";
                success = true;
            }

        } catch (Exception e) {
            log("[Workflow] 异常: " + e.getMessage());
            finalStatus = "EXCEPTION: " + e.getMessage();
        }

        try {
            JSONObject response = new JSONObject();
            response.put("jsonrpc", "2.0");
            response.put("id", rpcId);
            response.put("__dir", "s2c");

            JSONObject resultData = new JSONObject();
            resultData.put("status", success ? "ok" : "fail");
            resultData.put("workflow", "teld_punch");
            resultData.put("detail", finalStatus);
            response.put("result", resultData);

            String respStr = response.toString();
            if (callback != null) {
                callback.onResponse(respStr);
            } else {
                sendReply(respStr);
            }
            log(">>> [Workflow] 打卡结果交付完成: " + finalStatus);
        } catch (Exception ignored) {}
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null) currentPackage = pkg.toString();
            if (cls != null) currentActivity = cls.toString();
        }
    }

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

    private boolean launchAppByName(String identifier) {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(identifier);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
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
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
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
                    node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
        return clicked;
    }

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
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.height() > 0) {
            clickCoordinate(rect.centerX(), rect.centerY());
            return true;
        }
        return false;
    }

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

    private void clickCoordinate(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    private void swipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        dispatchGesture(builder.build(), null, null);
    }

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

    private JSONArray dumpCurrentUiPruned() {
        JSONArray array = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return array;
        try {
            int[] currentBytes = new int[]{2};
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

    public void handleCommand(String jsonStr) {
        dispatchRpcMessageAsync(jsonStr, null);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (rpcServerSocket != null) {
            try { rpcServerSocket.close(); } catch(Exception ignored) {}
        }
        if (rpcServerThread != null) {
            rpcServerThread.interrupt();
        }
        if (clipboardManager != null && clipListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
    }
}
