package com.scrc.android

/**
 * @param newDisplay scrcpy `new_display` 值，如 `1080x2400/420`；null 表示整机镜像
 * @param startAppPackage 独立应用模式下要启动的包名；null 表示不自动启动
 */
data class SessionConfig(
    val host: String,
    val port: Int,
    val maxSize: Int,
    val newDisplay: String? = null,
    val startAppPackage: String? = null,
) {
    val isAppMode: Boolean get() = !newDisplay.isNullOrBlank()
}
