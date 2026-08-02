package com.scrc.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoder {
    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var width: Int = 0
    private var height: Int = 0
    private var surface: Surface? = null
    private val started = AtomicBoolean(false)
    private var pendingConfig: ByteArray? = null

    @Synchronized
    fun setSurface(surface: Surface?) {
        this.surface = surface
        maybeStart()
    }

    fun setCodecId(codecId: Int) {
        mime = when (codecId) {
            ScrcpyConstants.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ScrcpyConstants.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> throw IllegalArgumentException("Unsupported codec id: 0x${codecId.toString(16)}")
        }
    }

    @Synchronized
    fun onSessionSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (width == w && height == h && started.get()) return
        width = w
        height = h
        releaseCodecOnly()
        maybeStart()
    }

    fun getWidth(): Int = width
    fun getHeight(): Int = height

    @Synchronized
    fun decode(data: ByteArray, config: Boolean, ptsUs: Long) {
        if (config) {
            pendingConfig = data
        }
        val c = codec
        if (c == null || !started.get()) {
            return
        }

        feed(c, data, config, ptsUs)
        drain(c)
    }

    @Synchronized
    fun release() {
        started.set(false)
        pendingConfig = null
        releaseCodecOnly()
        surface = null
        width = 0
        height = 0
    }

    private fun maybeStart() {
        val surf = surface ?: return
        if (width <= 0 || height <= 0) return
        if (started.get()) return

        val format = MediaFormat.createVideoFormat(mime, width, height)
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surf, null, 0)
        c.start()
        codec = c
        started.set(true)
        Log.i(TAG, "decoder started mime=$mime ${width}x$height")

        pendingConfig?.let { cfg ->
            feed(c, cfg, config = true, ptsUs = 0L)
            drain(c)
        }
    }

    private fun feed(c: MediaCodec, data: ByteArray, config: Boolean, ptsUs: Long) {
        val index = c.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val input = c.getInputBuffer(index) ?: return
        input.clear()
        input.put(data)
        val flags = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> c.releaseOutputBuffer(outIndex, true)
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.d(TAG, "output format: ${c.outputFormat}")
                else -> break
            }
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
