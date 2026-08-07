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
 * 异步硬解：读流线程只投递最新帧，解码线程追帧丢旧，降低触控体感延迟。
 */
class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 2_000L
        private const val LATENCY_ALPHA = 0.2
    }

    private data class Packet(
        val data: ByteArray,
        val config: Boolean,
        val ptsUs: Long,
        val arrivalNanos: Long,
    )

    private var codec: MediaCodec? = null
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var width: Int = 0
    private var height: Int = 0
    private var surface: Surface? = null
    private val started = AtomicBoolean(false)
    private var pendingConfig: ByteArray? = null

    private val renderedFrames = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)
    @Volatile private var latencyEmaMs = 0.0

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    /** 仅保留最新待解码帧（config 包立即投递） */
    private var latest: Packet? = null
    private var decodeScheduled = false

    @Synchronized
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
        droppedFrames.set(0)
        return fps to latencyEmaMs.toInt()
    }

    fun decode(data: ByteArray, config: Boolean, ptsUs: Long, arrivalNanos: Long = 0L) {
        ensureThread()
        val h = handler ?: return
        if (config) {
            h.post {
                pendingConfig = data
                val c = codec
                if (c != null && started.get()) {
                    feed(c, data, config = true, ptsUs = 0L)
                    drainRenderLatest(c)
                }
            }
            return
        }
        val packet = Packet(data, false, ptsUs, arrivalNanos)
        synchronized(this) {
            if (latest != null) droppedFrames.incrementAndGet()
            latest = packet
            if (!decodeScheduled) {
                decodeScheduled = true
                h.post { pump() }
            }
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
        synchronized(this) {
            latest = null
            decodeScheduled = false
        }
        renderedFrames.set(0)
        droppedFrames.set(0)
        latencyEmaMs = 0.0
    }

    private fun pump() {
        while (true) {
            val packet = synchronized(this) {
                val p = latest
                latest = null
                if (p == null) {
                    decodeScheduled = false
                    return
                }
                p
            }
            val c = codec
            if (c == null || !started.get()) continue
            feed(c, packet.data, config = false, ptsUs = packet.ptsUs)
            drainRenderLatest(c, packet.arrivalNanos)
        }
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
        Log.i(TAG, "decoder started mime=$mime ${width}x$height lowLatency")

        pendingConfig?.let { cfg ->
            feed(c, cfg, config = true, ptsUs = 0L)
            drainRenderLatest(c)
        }
    }

    private fun feed(c: MediaCodec, data: ByteArray, config: Boolean, ptsUs: Long) {
        var tries = 0
        while (tries < 8) {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return
                input.clear()
                input.put(data)
                val flags = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
            // 输入堵了：先尽量掏空输出再重试
            drainRenderLatest(c)
            tries++
        }
        droppedFrames.incrementAndGet()
    }

    /** 输出积压时只渲染最后一帧，中间帧 release(render=false) */
    private fun drainRenderLatest(c: MediaCodec, arrivalNanos: Long = 0L) {
        val info = MediaCodec.BufferInfo()
        var pendingIndex = -1
        var pendingArrival = 0L
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> {
                    val usable = info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    if (pendingIndex >= 0) {
                        c.releaseOutputBuffer(pendingIndex, false)
                        if (usable) droppedFrames.incrementAndGet()
                    }
                    if (usable) {
                        pendingIndex = outIndex
                        pendingArrival = arrivalNanos
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
            if (pendingArrival > 0L) {
                val ms = (SystemClock.elapsedRealtimeNanos() - pendingArrival) / 1_000_000.0
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
