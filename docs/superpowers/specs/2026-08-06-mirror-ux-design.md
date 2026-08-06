# Mirror UX 增强设计

日期：2026-08-06  
范围：投屏体验 + 双向剪贴板  
状态：已批准

## 目标

在现有 scrcpy Android 控制端上增加五项能力，提升投屏可用性：

1. 双向剪贴板自动同步
2. 画中画（PiP）
3. 双指手势（缩放 / 平移 / 双击）
4. 音量键映射到被控端
5. FPS / 延迟显示

不做：截图、录像、音频镜像、手势宏。

## 架构概览

```
MirrorActivity
  ├─ ClipboardBridge          # 本机剪贴板 ↔ SET_CLIPBOARD / DEVICE CLIPBOARD
  ├─ MirrorGestureController  # 双指缩放平移，单指交给原触控
  ├─ StatsTicker              # 读 VideoDecoder 计数，刷新状态行
  └─ ScrcpySession
       ├─ ControlMessageWriter   # 出站（已有）
       ├─ DeviceMessageReader    # 入站（新增）
       └─ VideoDecoder           # 帧计数 + 到达→渲染延迟（扩展）
```

## 1. 双向剪贴板

### 协议

- **被控 → 本机**：control 入站 `DeviceMessage.TYPE_CLIPBOARD = 0`  
  格式：`type(u8) + length(u32 BE) + utf8`
- **本机 → 被控**：已有 `MSG_SET_CLIPBOARD`（`paste=false`，避免自动粘贴打断对方）  
  格式保持现有实现：`type + sequence(u64=0) + paste(u8) + length(u32) + utf8`
- `TYPE_ACK_CLIPBOARD`：sequence=0 时忽略即可

### 行为

- `ScrcpySession` 在 control 建连后启动协程循环读取入站消息，剪贴板文本回调到 `Listener.onRemoteClipboard(text)`。
- `ClipboardBridge`：
  - 注册 `ClipboardManager.OnPrimaryClipChangedListener`
  - 本机变化且文本 ≠ `lastSynced` → `setClipboard(text, paste=false)`，更新 `lastSynced`
  - 收到远端文本且 ≠ `lastSynced` → 写入本机剪贴板，更新 `lastSynced`
- 键盘输入路径的中文 `injectOrPaste`（paste=true）保持不变，写入时同步刷新 `lastSynced`，避免回声。
- Android 10+ 后台读剪贴板受限：本 App 在前台投屏时可读；进入 PiP 后若系统限制读失败则静默跳过，不报错打断投屏。

### 文件

- 新增 `DeviceMessageReader.kt`
- 新增 `ClipboardBridge.kt`
- 改 `ScrcpySession.kt`、`MirrorActivity.kt`、`ScrcpyConstants.kt`

## 2. 画中画

### 行为

- `MirrorActivity` 声明 `supportsPictureInPicture`、`configChanges` 含 `screenLayout|smallestScreenSize`。
- 浮层增加「画中画」按钮；用户按 Home 时 `onUserLeaveHint` 自动进入 PiP（已连接时）。
- 进入 PiP：隐藏浮层、键盘；暂停本机剪贴板监听（可选，避免无意义回调）。
- PiP 内不注入触控。
- `onPictureInPictureModeChanged(false)`：恢复浮层可见性与剪贴板监听。
- 断连或用户点断开：若在 PiP 则 `finish()`。

### 文件

- 改 `AndroidManifest.xml`、`activity_mirror.xml`、`strings.xml`、`MirrorActivity.kt`

## 3. 双指手势

### 行为（纯客户端变换）

- 作用在包裹 `SurfaceView` 的容器上，使用 `scaleX/Y` + `translationX/Y`（或 `Matrix`），不改 letterbox 布局逻辑的基准尺寸。
- **捏合**：缩放范围 `1f..4f`，以双指中点为锚。
- **双指拖**（scale > 1）：平移；限制平移使画面至少留一部分在可视区内。
- **双击**：在 `1f` 与 `2f` 之间切换（锚点为点击位置）。
- **指针规则**：
  - 单指：沿用现有 `handleTouch` → 注入被控
  - 双指及以上：不注入触控；交给手势控制器
- 缩放复位：双击回 1x，或浮层无需专门按钮（保持简单）。

### 文件

- 新增 `MirrorGestureController.kt`
- 改 `MirrorActivity.kt`（触摸分发）、必要时微调 `activity_mirror.xml`（手势作用的容器）

## 4. 音量键映射

### 行为

- `MirrorActivity.dispatchKeyEvent`：在 `controlReady` 且非本机输入框焦点时，拦截 `KEYCODE_VOLUME_UP/DOWN`。
- DOWN/UP 成对注入被控（`injectKeycode` 或 `injectKeyClick`）。
- 返回 `true`，不改变本机媒体音量。
- PiP 模式下同样映射（若 Activity 仍能收到按键）。

### 文件

- 改 `MirrorActivity.kt`；常量可用 Android `KeyEvent` 已有 keycode

## 5. FPS / 延迟

### 指标

- **FPS**：`VideoDecoder` 在成功 `releaseOutputBuffer(..., true)` 时递增计数；`MirrorActivity` 每秒取样并清零窗口计数。
- **延迟 (ms)**：每个非 config 包在 `demuxVideo` 读到时记 `SystemClock.elapsedRealtimeNanos()`，随 payload 传入 decoder；在 `releaseOutputBuffer` 时算差，维护指数滑动平均（α≈0.2）。
- 状态行格式：保留原连接文案时，另起或拼接 ` · 30fps · 28ms`；约 1Hz 刷新，避免刷 UI。

### 文件

- 改 `VideoDecoder.kt`、`ScrcpySession.kt`（传到达时间）、`MirrorActivity.kt`

## 错误处理

- control 入站读失败：若 session 仍 running，走现有 `stopInternal` / 重连路径。
- 剪贴板权限/安全异常：catch 后 Log.w，不中断投屏。
- PiP 不支持的设备：隐藏「画中画」按钮（`PackageManager.hasSystemFeature` 或 `Activity.isInPictureInPictureMode` 可用性判断）。

## 测试计划

- 本机复制 → 被控可粘贴；被控复制 → 本机剪贴板更新；来回各一次确认无回声风暴。
- 捏合缩放、双指平移、双击复位；缩放时单指仍可点击被控。
- 音量键只改变被控音量条。
- 浮层状态出现合理 fps/ms；黑屏静止时 fps 下降。
- 进入/退出 PiP，投屏不断；PiP 中点回全屏可继续触控。
- OTG 与无线两种连接各测一轮剪贴板与音量键。

## 实现顺序建议

1. DeviceMessageReader + Session 入站 + ClipboardBridge  
2. VideoDecoder 统计 + 状态行  
3. 音量键映射  
4. MirrorGestureController  
5. PiP  
