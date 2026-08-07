package com.scrc.android

enum class VideoCodecOption(val serverValue: String, val label: String) {
    H264("h264", "H.264"),
    H265("h265", "H.265"),
    ;

    companion object {
        fun fromServer(value: String): VideoCodecOption =
            entries.firstOrNull { it.serverValue == value } ?: H264

        fun labels(): Array<String> = entries.map { it.label }.toTypedArray()

        fun fromLabel(label: String): VideoCodecOption =
            entries.firstOrNull { it.label == label } ?: H264
    }
}

/**
 * 投屏性能档位。分辨率仍由 [ResolutionPreset] 单独控制。
 * @param maxFps 0 = 不限制
 */
enum class PerformancePreset(
    val label: String,
    val videoBitRate: Int,
    val maxFps: Int,
    val videoCodec: VideoCodecOption,
    val lowLatencyEncode: Boolean,
) {
    BALANCED("均衡", 8_000_000, 0, VideoCodecOption.H264, false),
    LOW_LATENCY("低延迟", 4_000_000, 60, VideoCodecOption.H264, true),
    WEAK_NET("弱网", 2_000_000, 30, VideoCodecOption.H265, false),
    QUALITY("画质", 12_000_000, 0, VideoCodecOption.H265, false),
    CUSTOM("自定义", 8_000_000, 0, VideoCodecOption.H264, false),
    ;

    fun toVideoOptions(
        customBitRate: Int? = null,
        customMaxFps: Int? = null,
        customCodec: VideoCodecOption? = null,
        customLowLatency: Boolean? = null,
    ): VideoOptions {
        if (this != CUSTOM) {
            return VideoOptions(videoBitRate, maxFps, videoCodec, lowLatencyEncode)
        }
        return VideoOptions(
            videoBitRate = customBitRate?.takeIf { it > 0 } ?: videoBitRate,
            maxFps = customMaxFps?.coerceAtLeast(0) ?: maxFps,
            videoCodec = customCodec ?: videoCodec,
            lowLatencyEncode = customLowLatency ?: lowLatencyEncode,
        )
    }

    companion object {
        fun labels(): Array<String> = entries.map { it.label }.toTypedArray()

        fun fromLabel(label: String): PerformancePreset =
            entries.firstOrNull { it.label == label } ?: BALANCED
    }
}

data class VideoOptions(
    val videoBitRate: Int,
    val maxFps: Int,
    val videoCodec: VideoCodecOption,
    val lowLatencyEncode: Boolean,
) {
    fun summary(): String = buildString {
        append(formatBitRate(videoBitRate))
        append(" · ")
        append(if (maxFps > 0) "${maxFps}fps" else "不限帧率")
        append(" · ")
        append(videoCodec.label)
        if (lowLatencyEncode) append(" · 低延迟编码")
    }
}

object BitRateChoices {
    val VALUES = intArrayOf(1_000_000, 2_000_000, 4_000_000, 8_000_000, 12_000_000, 16_000_000)

    fun labels(): Array<String> = VALUES.map { formatBitRate(it) }.toTypedArray()

    fun nearest(value: Int): Int = VALUES.minBy { kotlin.math.abs(it - value) }
}

object FpsChoices {
    /** 0 = 不限制 */
    val VALUES = intArrayOf(0, 15, 30, 45, 60)

    fun labels(): Array<String> = VALUES.map {
        if (it == 0) "不限帧率" else "${it} fps"
    }.toTypedArray()

    fun nearest(value: Int): Int = VALUES.minBy { kotlin.math.abs(it - value) }
}

fun formatBitRate(bps: Int): String = when {
    bps % 1_000_000 == 0 -> "${bps / 1_000_000} Mbps"
    bps % 1_000 == 0 -> "${bps / 1_000} kbps"
    else -> "$bps bps"
}
