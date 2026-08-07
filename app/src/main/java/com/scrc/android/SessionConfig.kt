package com.scrc.android

/**
 * @param newDisplay scrcpy `new_display` 值，如 `1080x2400/420`；null 表示整机镜像
 * @param startAppPackage 独立应用模式下要启动的包名；null 表示不自动启动
 * @param usb true 时走 OTG USB ADB，忽略 host/port
 * @param maxFps 0 = 不限制采集帧率
 */
data class SessionConfig(
    val host: String = "",
    val port: Int = 5555,
    val maxSize: Int,
    val newDisplay: String? = null,
    val startAppPackage: String? = null,
    val usb: Boolean = false,
    val videoBitRate: Int = 8_000_000,
    val maxFps: Int = 0,
    val videoCodec: String = VideoCodecOption.H264.serverValue,
    val lowLatencyEncode: Boolean = false,
) {
    val isAppMode: Boolean get() = !newDisplay.isNullOrBlank()
}
