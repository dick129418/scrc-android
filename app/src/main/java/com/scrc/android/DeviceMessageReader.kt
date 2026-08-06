package com.scrc.android

import android.util.Log
import okio.BufferedSource
import java.io.IOException

/** 解析 scrcpy control 入站 DeviceMessage。 */
class DeviceMessageReader(
    private val source: BufferedSource,
    private val onClipboard: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DeviceMsg"
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2
    }

    fun readLoop(running: () -> Boolean) {
        while (running()) {
            val type = source.readByte().toInt() and 0xff
            when (type) {
                TYPE_CLIPBOARD -> {
                    val len = source.readInt()
                    if (len < 0 || len > 2 * 1024 * 1024) {
                        throw IOException("clipboard length=$len")
                    }
                    val text = if (len == 0) "" else {
                        source.readByteArray(len.toLong()).toString(Charsets.UTF_8)
                    }
                    onClipboard(text)
                }
                TYPE_ACK_CLIPBOARD -> source.skip(8)
                TYPE_UHID_OUTPUT -> {
                    source.skip(2)
                    val size = source.readShort().toInt() and 0xffff
                    if (size > 0) source.skip(size.toLong())
                }
                else -> {
                    Log.w(TAG, "unknown device msg type=$type, stop reader")
                    return
                }
            }
        }
    }
}
