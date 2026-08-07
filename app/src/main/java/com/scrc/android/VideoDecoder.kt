package com.scrc.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 异步硬解：码流包必须按序完整喂入（H.264/H.265 丢包会花屏）；
 * 仅在输出侧跳过中间帧渲染以压低显示延迟。
 */
class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val LATENCY_ALPHA = 0.2
    }

    private var codec: MediaCodec? = null
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var width: Int = 0
    private var height: Int = 0
    private var surface: Surface? = null
    private val started = AtomicBoolean(false)
    private var pendingConfig: ByteArray? = null

    private val renderedFrames = AtomicInteger(0)
    @Volatile private var latencyEmaMs = 0.0

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun setSurface(surface: Surface?) {
        this.surface = surface
        ensureThread()
        handler?.post { maybeStart() }
    }

    fun setCodecId(codecId: Int) {
        mime = when (codecId) {
            ScrcpyConstants.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ScrcpyConstants.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> throw IllegalArgumentException("Unsupported codec id: 0x${codecId.toString(16)}")
        }
    }

    fun onSessionSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        ensureThread()
        handler?.post {
            if (width == w && height == h && started.get()) return@post
            width = w
            height = h
            releaseCodecOnly()
            maybeStart()
        }
    }

    fun getWidth(): Int = width
    fun getHeight(): Int = height

    /** @return fps since last snapshot, ema latency ms */
    fun snapshotStats(): Pair<Int, Int> {
        val fps = renderedFrames.getAndSet(0)
        return fps to latencyEmaMs.toInt()
    }

    fun decode(data: ByteArray, config: Boolean, ptsUs: Long, arrivalNanos: Long = 0L) {
        ensureThread()
        // 每包单独 post，保持顺序；绝不能在解码前合并/丢弃码流包
        handler?.post {
            if (config) {
                pendingConfig = data
                val c = codec
                if (c != null && started.get()) {
                    feed(c, data, config = true, ptsUs = 0L)
                    drainRenderLatest(c)
                }
                return@post
            }
            val c = codec
            if (c == null || !started.get()) return@post
            feed(c, data, config = false, ptsUs = ptsUs)
            drainRenderLatest(c, arrivalNanos)
        }
    }

    fun release() {
        val t = thread
        val h = handler
        if (h == null || t == null) {
            resetState()
            releaseCodecOnly()
            surface = null
            width = 0
            height = 0
            return
        }
        h.post {
            resetState()
            releaseCodecOnly()
            surface = null
            width = 0
            height = 0
            t.quit()
        }
        t.join(800)
        thread = null
        handler = null
    }

    private fun ensureThread() {
        if (thread?.isAlive == true && handler != null) return
        val t = HandlerThread("scrcpy-decode").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun resetState() {
        started.set(false)
        pendingConfig = null
        renderedFrames.set(0)
        latencyEmaMs = 0.0
    }

    private fun maybeStart() {
        val surf = surface ?: return
        if (width <= 0 || height <= 0) return
        if (started.get()) return

        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surf, null, 0)
        c.start()
        codec = c
        started.set(true)
        Log.i(TAG, "decoder started mime=$mime ${width}x$height")

        pendingConfig?.let { cfg ->
            feed(c, cfg, config = true, ptsUs = 0L)
            drainRenderLatest(c)
        }
    }

    /** 阻塞直到写入成功；解码前丢包会导致花屏马赛克 */
    private fun feed(c: MediaCodec, data: ByteArray, config: Boolean, ptsUs: Long) {
        while (true) {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return
                input.clear()
                if (input.remaining() < data.size) {
                    Log.e(TAG, "input buffer too small: ${input.remaining()} < ${data.size}")
                    c.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    return
                }
                input.put(data)
                val flags = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
            drainRenderLatest(c)
        }
    }

    /** 输出积压时只渲染最后一帧，中间帧不送显 */
    private fun drainRenderLatest(c: MediaCodec, arrivalNanos: Long = 0L) {
        val info = MediaCodec.BufferInfo()
        var pendingIndex = -1
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> {
                    val usable = info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    if (pendingIndex >= 0) {
                        c.releaseOutputBuffer(pendingIndex, false)
                    }
                    if (usable) {
                        pendingIndex = outIndex
                    } else {
                        c.releaseOutputBuffer(outIndex, false)
                    }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.d(TAG, "output format: ${c.outputFormat}")
                else -> break
            }
        }
        if (pendingIndex >= 0) {
            renderedFrames.incrementAndGet()
            if (arrivalNanos > 0L) {
                val ms = (SystemClock.elapsedRealtimeNanos() - arrivalNanos) / 1_000_000.0
                latencyEmaMs =
                    if (latencyEmaMs <= 0.0) ms
                    else latencyEmaMs * (1 - LATENCY_ALPHA) + ms * LATENCY_ALPHA
            }
            c.releaseOutputBuffer(pendingIndex, true)
        }
    }

    private fun releaseCodecOnly() {
        started.set(false)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }
}
