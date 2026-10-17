# 139 移动云手机 - 无障碍自动化服务 (CloudAutoService)

专为 **中国移动 139 云手机（基于华为云 KooPhone 容器）** 定制开发的轻量级后台无障碍守护服务。

---

## 核心设计与特性

1. **直接复用 WebRTC 信令通道**：
   - 无需外网 IP、无需内网穿透端口；
   - PC 端通过 WebRTC DataChannel 发送指令，安卓端无障碍服务毫秒级捕获并执行。
2. **专为 Android 10+ 避坑优化**：
   - 锁定 `targetSdkVersion 28`（Android 9 Pie），彻底绕过 Android 10+ 对后台应用读取剪贴板的系统封锁，保证服务全天候后台常驻监听。
   - `minSdkVersion 24`（Android 7.0+），完美支持原生 `dispatchGesture` 模拟点击与滑动。
3. **双向通信支持**：
   - **下发指令**：PC $\xrightarrow{\text{WebRTC}}$ 云手机无障碍服务；
   - **回传结果**：无障碍服务执行完点击或抓取完屏幕文字后，通过 WebRTC `_cloud_clipboard` 原路回传给 PC 控制端。

---

## 控制指令协议 (JSON)

### 1. 按文字点击（如打卡/签到按钮）
```json
{
  "action": "click_text",
  "text": "立即打卡"
}
```

### 2. 按控件 View ID 点击
```json
{
  "action": "click_id",
  "id": "com.target.app:id/btn_confirm"
}
```

### 3. 直接填充文本（免弹输入法键盘）
```json
{
  "action": "set_text",
  "id": "com.target.app:id/et_phone",
  "text": "13912345678"
}
```

### 4. 模拟坐标点击
```json
{
  "action": "click_coord",
  "x": 360,
  "y": 640
}
```

### 5. 模拟滑动手势
```json
{
  "action": "swipe",
  "x1": 360,
  "y1": 800,
  "x2": 360,
  "y2": 200,
  "duration": 300
}
```

### 6. 抓取当前页面所有文字与控件布局 (Dump UI)
```json
{
  "action": "dump_ui"
}
```

### 7. 全局系统动作
```json
{
  "action": "global",
  "key": "home"  // 可选: "home", "back", "recents", "notifications"
}
```
