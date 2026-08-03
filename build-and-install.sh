#!/usr/bin/env bash
# 编译 debug APK 并安装到指定设备。改下面变量即可，不要用命令行参数。
set -euo pipefail
cd "$(dirname "$0")"

SERIAL="10.21.33.39:5555"
SKIP_BUILD=0          # 1=只装已有 APK
FORCE_REINSTALL=1     # 1=签名冲突时先卸载再装
APP_ID="com.scrc.android"
APK="app/build/outputs/apk/debug/app-debug.apk"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

find_adb() {
  command -v adb 2>/dev/null && return
  for p in "$SDK_DIR/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [[ -x "$p" ]] && { echo "$p"; return; }
  done
  echo "未找到 adb" >&2
  exit 1
}

ADB="$(find_adb)"

if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$SDK_DIR" > local.properties
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "==> 编译 assembleDebug ..."
  chmod +x gradlew
  ./gradlew assembleDebug
else
  echo "==> 跳过编译"
fi

[[ -f "$APK" ]] || { echo "APK 不存在: $APK" >&2; exit 1; }

echo "==> 连接 $SERIAL ..."
"$ADB" connect "$SERIAL" >/dev/null || true
state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$state" == "device" ]] || {
  echo "设备未就绪: $SERIAL (state=${state:-unknown})" >&2
  exit 1
}

echo "==> 安装 $APK ..."
if ! out="$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1)"; then
  echo "$out"
  if [[ "$FORCE_REINSTALL" -eq 1 && "$out" == *UPDATE_INCOMPATIBLE* ]]; then
    echo "==> 签名冲突，卸载后重装 ..."
    "$ADB" -s "$SERIAL" uninstall "$APP_ID" || true
    "$ADB" -s "$SERIAL" install "$APK"
  else
    echo "安装失败。小米机请打开「开发者选项 → USB 安装」并在弹窗点允许。" >&2
    exit 1
  fi
else
  echo "$out"
fi

echo "==> 完成 ($SERIAL)"
