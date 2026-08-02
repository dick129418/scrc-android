# Scrcpy Android

Android → Android 投屏控制端（方案 A）：控制手机安装本 APK，通过无线 ADB 推送并启动官方 `scrcpy-server`，实现镜像与触控。

基于 **scrcpy 4.1** 协议 + [dadb](https://github.com/mobile-dev-inc/dadb)。

## 使用前准备（被控手机）

两机需同一 Wi‑Fi，或由控制端开热点。

### 推荐：先用电脑开一次无线 ADB

1. 被控手机开启「开发者选项 → USB 调试」
2. USB 连接电脑，执行：

```bash
adb tcpip 5555
adb connect <被控手机IP>:5555
```

3. 拔掉 USB 后，确认仍可 `adb connect <IP>:5555`

也可在开发者选项中开启「无线调试」，使用其中显示的 **IP:端口**（不是配对端口）。若控制端是首次连接，需在被控手机上点「允许 USB 调试」。

## 使用（控制手机）

1. 用 Android Studio 打开本项目，运行到控制手机
2. 输入被控手机 IP 与端口（默认 `5555`）
3. 点「连接并投屏」
4. 被控端弹出授权时点允许
5. 在画面上触控；底部可返回 / Home / 断开

## 工程说明

| 模块 | 作用 |
|------|------|
| `ScrcpySession` | dadb 连接、推送 server、建链、收流 |
| `VideoDecoder` | MediaCodec 硬解 H.264/H.265 |
| `ControlMessageWriter` | 触控 / 按键注入 |
| `assets/scrcpy-server-v4.1` | 官方服务端（须与协议版本一致） |

## 构建

```bash
# Windows 示例
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 限制

- 需要被控端开启无线 ADB（非「两个普通 APK 点一下就连」）
- Android 11+「无线调试」若未配对，可能连不上；优先用 `adb tcpip 5555`
- 协议随 scrcpy 版本变化，升级 server 时需同步改客户端解析逻辑
