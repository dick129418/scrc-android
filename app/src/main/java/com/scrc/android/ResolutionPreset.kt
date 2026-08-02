package com.scrc.android

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt

enum class ResolutionPreset(
    val label: String,
    /** null = compute from device; -1 = custom input; 0 = unlimited */
    private val fixedMaxSize: Int?,
) {
    ADAPT("适配本机屏幕", null),
    P720("720p", 720),
    P1080("1080p", 1080),
    P1280("1280", 1280),
    P1920("1920p", 1920),
    ORIGINAL("原始分辨率", 0),
    CUSTOM("自定义", -1),
    ;

    fun resolveMaxSize(context: Context, customValue: Int?): Int {
        return when (fixedMaxSize) {
            null -> adaptToLocalScreen(context)
            -1 -> customValue?.takeIf { it > 0 } ?: adaptToLocalScreen(context)
            else -> fixedMaxSize
        }
    }

    companion object {
        fun labels(): Array<String> = entries.map { it.label }.toTypedArray()

        fun fromLabel(label: String): ResolutionPreset =
            entries.firstOrNull { it.label == label } ?: ADAPT

        /**
         * Limit video longer-side to the controller panel's longer side,
         * with a small margin and even alignment for encoders.
         */
        fun adaptToLocalScreen(context: Context): Int {
            val metrics = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val longer = max(metrics.widthPixels, metrics.heightPixels)
            // Keep a bit of headroom for UI chrome; clamp to common range.
            val target = (longer * 0.95f).roundToInt().coerceIn(480, 2560)
            return target and 0x7FFFFFFE // even
        }
    }
}
