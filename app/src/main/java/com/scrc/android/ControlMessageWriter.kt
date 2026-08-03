package com.scrc.android

import android.util.Log
import okio.BufferedSink
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ControlMessageWriter(
    private val sink: BufferedSink,
) {
    companion object {
        private const val TAG = "ControlMsg"
    }

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scrcpy-control").apply { isDaemon = true }
    }

    fun injectTouch(
        action: Int,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float = 1f,
    ) {
        val buf = ByteBuffer.allocate(32)
        buf.put(ScrcpyConstants.MSG_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(ScrcpyConstants.POINTER_ID_FINGER)
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(screenWidth.coerceIn(0, 0xFFFF).toShort())
        buf.putShort(screenHeight.coerceIn(0, 0xFFFF).toShort())
        buf.putShort((pressure.coerceIn(0f, 1f) * 0xFFFF).toInt().toShort())
        buf.putInt(0) // action_button
        buf.putInt(0) // buttons
        writeAsync(buf.array())
    }

    fun injectKeycode(keycode: Int, action: Int, repeat: Int = 0, metastate: Int = 0) {
        val buf = ByteBuffer.allocate(14)
        buf.put(ScrcpyConstants.MSG_INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(repeat)
        buf.putInt(metastate)
        writeAsync(buf.array())
    }

    fun injectKeyClick(keycode: Int, metastate: Int = 0) {
        injectKeycode(keycode, ScrcpyConstants.ACTION_DOWN, metastate = metastate)
        injectKeycode(keycode, ScrcpyConstants.ACTION_UP, metastate = metastate)
    }

    /** 注入文本（UTF-8），仅可靠支持 ASCII；中文等请用 setClipboard(paste=true)。 */
    fun injectText(text: String) {
        if (text.isEmpty()) return
        var remain = text
        while (remain.isNotEmpty()) {
            var end = remain.length
            var chunk = remain.toByteArray(Charsets.UTF_8)
            while (chunk.size > ScrcpyConstants.INJECT_TEXT_MAX_LENGTH && end > 1) {
                end--
                chunk = remain.substring(0, end).toByteArray(Charsets.UTF_8)
            }
            val bytes = ByteArray(5 + chunk.size)
            bytes[0] = ScrcpyConstants.MSG_INJECT_TEXT.toByte()
            bytes[1] = (chunk.size ushr 24).toByte()
            bytes[2] = (chunk.size ushr 16).toByte()
            bytes[3] = (chunk.size ushr 8).toByte()
            bytes[4] = chunk.size.toByte()
            System.arraycopy(chunk, 0, bytes, 5, chunk.size)
            writeAsync(bytes)
            remain = remain.substring(end)
        }
    }

    /**
     * 设置被控端剪贴板；paste=true 时立刻粘贴到当前焦点（用于中文等非 ASCII）。
     * 协议：type + sequence(u64) + paste(u8) + length(u32) + utf8
     */
    fun setClipboard(text: String, paste: Boolean = true) {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(14 + utf8.size)
        bytes[0] = ScrcpyConstants.MSG_SET_CLIPBOARD.toByte()
        // sequence = 0（不需要 ACK）
        bytes[9] = if (paste) 1 else 0
        bytes[10] = (utf8.size ushr 24).toByte()
        bytes[11] = (utf8.size ushr 16).toByte()
        bytes[12] = (utf8.size ushr 8).toByte()
        bytes[13] = utf8.size.toByte()
        System.arraycopy(utf8, 0, bytes, 14, utf8.size)
        writeAsync(bytes)
    }

    /** ASCII 用按键注入，其余用剪贴板粘贴。 */
    fun injectOrPaste(text: String) {
        if (text.isEmpty()) return
        if (text.any { it.code > 0x7f }) setClipboard(text, paste = true)
        else injectText(text)
    }

    fun backOrScreenOn(action: Int) {
        writeAsync(byteArrayOf(ScrcpyConstants.MSG_BACK_OR_SCREEN_ON.toByte(), action.toByte()))
    }

    /**
     * 控制被控端物理屏幕电源。
     * @param on true=亮屏，false=黑屏（省电，投屏画面不受影响）
     */
    fun setDisplayPower(on: Boolean) {
        writeAsync(displayPowerBytes(on))
    }

    /**
     * 启动应用。包名前加 `+` 表示先 force-stop。
     * 协议：1 字节 type + string_tiny（1 字节长度 + UTF-8）。
     */
    fun startApp(name: String) {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        require(utf8.size in 1..255) { "startApp name length invalid: ${utf8.size}" }
        val bytes = ByteArray(2 + utf8.size)
        bytes[0] = ScrcpyConstants.MSG_START_APP.toByte()
        bytes[1] = utf8.size.toByte()
        System.arraycopy(utf8, 0, bytes, 2, utf8.size)
        writeAsync(bytes)
    }

    /** 断开前同步恢复亮屏，避免异步任务来不及发出 */
    fun setDisplayPowerSync(on: Boolean) {
        writeSync(displayPowerBytes(on))
    }

    private fun displayPowerBytes(on: Boolean): ByteArray =
        byteArrayOf(
            ScrcpyConstants.MSG_SET_DISPLAY_POWER.toByte(),
            if (on) 1 else 0,
        )

    private fun writeAsync(bytes: ByteArray) {
        if (closed.get()) return
        executor.execute {
            writeSync(bytes)
        }
    }

    private fun writeSync(bytes: ByteArray) {
        if (closed.get()) return
        try {
            synchronized(lock) {
                if (closed.get()) return
                sink.write(bytes)
                sink.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "control write failed: ${e.message}")
        }
    }

    fun close() {
        closed.set(true)
        executor.shutdownNow()
    }
}
