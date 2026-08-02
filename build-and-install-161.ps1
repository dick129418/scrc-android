# 编译 debug APK 并安装到控制端 161
# 用法:
#   .\build-and-install-161.ps1
#   .\build-and-install-161.ps1 -Serial "192.168.0.161:5555"
#   .\build-and-install-161.ps1 -SkipBuild   # 只安装已有 APK

param(
    [string]$Serial = "192.168.0.161:5555",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$javaHome = "C:\Program Files\Android\Android Studio\jbr"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

$adb = $null
$adbPaths = @(
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)
if ($env:ANDROID_HOME) {
    $adbPaths += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
}
if ($env:ANDROID_SDK_ROOT) {
    $adbPaths += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
}
foreach ($candidate in $adbPaths) {
    if (Test-Path -LiteralPath $candidate) {
        $adb = $candidate
        break
    }
}
if (-not $adb) {
    Write-Error "未找到 adb.exe，请确认已安装 Android SDK platform-tools。"
}

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $javaHome)) {
        Write-Error "未找到 JAVA_HOME: $javaHome"
    }
    $env:JAVA_HOME = $javaHome
    $javaBin = Join-Path $javaHome "bin"
    $env:Path = $javaBin + [IO.Path]::PathSeparator + $env:Path
    Write-Host "==> 编译 assembleDebug ..." -ForegroundColor Cyan
    $gradle = Join-Path $PSScriptRoot "gradlew.bat"
    & cmd.exe /c "`"$gradle`" assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle 编译失败 (exit $LASTEXITCODE)"
    }
} else {
    Write-Host "==> 跳过编译 (-SkipBuild)" -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $apk)) {
    Write-Error "APK 不存在: $apk"
}

Write-Host "==> 连接 $Serial ..." -ForegroundColor Cyan
& $adb connect $Serial | Out-Host

$state = (& $adb -s $Serial get-state 2>$null | Out-String).Trim()
if ($state -ne "device") {
    Write-Error "设备未就绪: $Serial (state=$state)。请确认无线 ADB 已开启且同一网络。"
}

Write-Host "==> 安装 $apk ..." -ForegroundColor Cyan
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Error "adb install 失败 (exit $LASTEXITCODE)"
}

Write-Host "==> 完成" -ForegroundColor Green