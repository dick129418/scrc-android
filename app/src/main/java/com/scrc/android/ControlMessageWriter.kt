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

    fun injectKeyClick(keycode: Int) {
        injectKeycode(keycode, ScrcpyConstants.ACTION_DOWN)
        injectKeycode(keycode, ScrcpyConstants.ACTION_UP)
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
