package com.scrc.android

import android.content.Context
import android.util.Log
import android.view.Surface
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import okio.BufferedSource
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class ScrcpySession(
    private val context: Context,
    private val config: SessionConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onConnected(deviceName: String)
        fun onVideoSize(width: Int, height: Int)
        fun onDisconnected(error: String?)
        fun onRemoteClipboard(text: String) {}
    }

    companion object {
        private const val TAG = "ScrcpySession"
        /** 约 24.8 天，等价于连接期间不自动息屏 */
        private const val SCREEN_OFF_TIMEOUT_KEEP_AWAKE = "2147483647"
    }

    private val host: String get() = config.host
    private val port: Int get() = config.port
    private val maxSize: Int get() = config.maxSize

    private val running = AtomicBoolean(false)
    private val decoder = VideoDecoder()

    /** Separate ADB connections: shell drain must not share MessageQueue with video open(). */
    private var shellDadb: Dadb? = null
    private var dataDadb: Dadb? = null

    private var shellStream: AdbShellStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private var controlWriter: ControlMessageWriter? = null
    private var videoJob: Job? = null
    private var controlJob: Job? = null
    private var shellDrainJob: Job? = null
    private var sessionJob: Job = SupervisorJob()
    private var sessionScope = CoroutineScope(sessionJob + Dispatchers.IO)
    private val serverLog = AtomicReference("")
    private var scid: Int = 0

    /** 连接前的息屏超时，断开时写回；null 表示未改动 */
    private var savedScreenOffTimeout: String? = null
    private var stayAwakeApplied = false

    fun setSurface(surface: Surface?) {
        decoder.setSurface(surface)
    }

    fun getVideoWidth(): Int = decoder.getWidth()
    fun getVideoHeight(): Int = decoder.getHeight()

    fun control(): ControlMessageWriter? = controlWriter

    fun snapshotStats(): Pair<Int, Int> = decoder.snapshotStats()

    suspend fun start() = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        sessionJob = SupervisorJob()
        sessionScope = CoroutineScope(sessionJob + Dispatchers.IO)
        serverLog.set("")
        try {
            val keyPair = AdbKeyStore.getOrCreate(context)
            if (config.usb) {
                listener.onStatus("正在通过 OTG 连接 ADB …")
                val usbDadb = UsbAdb.connectReady(context, keyPair)
                // USB 只有一条 bulk 通道，shell 与 video 共用同一 Dadb（多路复用）
                shellDadb = usbDadb
                dataDadb = usbDadb
            } else {
                listener.onStatus("正在连接 ADB $host:$port …")
                shellDadb = createDadb(keyPair)
                dataDadb = createDadb(keyPair)
            }

            listener.onStatus("正在推送 scrcpy-server …")
            ScrcpyServerFiles.push(context, dataDadb!!)

            scid = Random.nextInt() and 0x7fffffff
            if (scid == 0) scid = 1
            val socketName = "scrcpy_%08x".format(scid)
            Log.i(TAG, "scid=$socketName")

            try {
                dataDadb!!.shell("pkill -f com.genymobile.scrcpy.Server >/dev/null 2>&1; true")
            } catch (_: Exception) {
            }
            delay(150)

            listener.onStatus(
                if (config.isAppMode) "正在启动独立应用虚拟屏 …" else "正在启动服务端 …",
            )
            enableStayAwake(dataDadb!!)
            startServer(shellDadb!!)

            // 虚拟屏创建可能更慢一些
            waitForServerReady(timeoutMs = if (config.isAppMode) 12_000 else 8_000)

            // tunnel_forward 协议顺序（与官方 client 一致）：
            // 1) 先连 video  2) 再连 control（server 在 accept 完全部 socket 前不会 sendDeviceMeta）
            // 3) 读 first socket 上的 dummy + device meta，再读 codec
            listener.onStatus("正在建立视频通道 …")
            val video = openAbstractSocket(dataDadb!!, socketName, retries = 50)
            videoStream = video
            val videoSource = video.source

            listener.onStatus("正在建立控制通道 …")
            val control = openAbstractSocket(dataDadb!!, socketName, retries = 30)
            controlStream = control
            controlWriter = ControlMessageWriter(control.sink)
            controlJob = sessionScope.launch {
                try {
                    DeviceMessageReader(control.source) { text ->
                        listener.onRemoteClipboard(text)
                    }.readLoop { running.get() }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "control inbound ended: ${e.message}")
                    }
                }
            }

            val dummy = videoSource.readByte()
            Log.i(TAG, "dummy=$dummy")
            if (dummy.toInt() != 0) {
                Log.w(TAG, "unexpected dummy byte: $dummy")
            }

            val nameBytes = videoSource.readByteArray(ScrcpyConstants.DEVICE_NAME_FIELD_LENGTH.toLong())
            val deviceName = nameBytes.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            Log.i(TAG, "deviceName=$deviceName")

            val codecId = videoSource.readInt()
            Log.i(TAG, "codecId=0x${codecId.toString(16)}")
            if (codecId == 0) throw IOException("设备关闭了视频流")
            if (codecId == 1) throw IOException("视频配置错误")
            decoder.setCodecId(codecId)

            // 官方 client 在建连后通过控制通道启动应用
            val pkg = config.startAppPackage?.trim().orEmpty()
            if (pkg.isNotEmpty()) {
                listener.onStatus("正在启动应用 $pkg …")
                controlWriter?.startApp("+$pkg")
            }

            val label = when {
                deviceName.isNotEmpty() -> deviceName
                config.usb -> "USB"
                else -> host
            }
            listener.onConnected(label)
            listener.onStatus(
                if (pkg.isNotEmpty()) "独立应用：$pkg" else "投屏中：$deviceName",
            )

            videoJob = sessionScope.launch {
                demuxVideo(videoSource)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed, serverLog=${serverLog.get()}", e)
            val detail = buildString {
                append(e.message ?: e.javaClass.simpleName)
                val log = serverLog.get()
                if (log.isNotBlank()) {
                    append(" | ")
                    append(log.takeLast(240).replace('\n', ' '))
                }
            }
            stopInternal(detail)
        }
    }

    fun stop() {
        stopInternal(null)
    }

    private fun createDadb(keyPair: AdbKeyPair): Dadb =
        Dadb.create(host, port, keyPair, connectTimeout = 10_000, socketTimeout = 0)

    private fun stopInternal(error: String?) {
        if (!running.getAndSet(false) && error == null) return
        running.set(false)
        videoJob?.cancel()
        videoJob = null
        controlJob?.cancel()
        controlJob = null
        shellDrainJob?.cancel()
        shellDrainJob = null
        // 服务端 cleanup 也会恢复，这里主动亮屏更稳妥
        try {
            controlWriter?.setDisplayPowerSync(true)
        } catch (_: Exception) {
        }
        controlWriter?.close()
        controlWriter = null
        decoder.release()
        try {
            videoStream?.close()
        } catch (_: Exception) {
        }
        try {
            controlStream?.close()
        } catch (_: Exception) {
        }
        try {
            shellStream?.close()
        } catch (_: Exception) {
        }
        videoStream = null
        controlStream = null
        shellStream = null
        // 先恢复息屏策略，再关 ADB
        restoreStayAwake(dataDadb)
        val shared = dataDadb != null && dataDadb === shellDadb
        try {
            dataDadb?.close()
        } catch (_: Exception) {
        }
        if (!shared) {
            try {
                shellDadb?.close()
            } catch (_: Exception) {
            }
        }
        dataDadb = null
        shellDadb = null
        sessionJob.cancel()
        listener.onDisconnected(error)
    }

    /**
     * 连接期间保持被控端唤醒：
     * - 先亮屏（若已灭）
     * - scrcpy stay_awake：充电时改 stay_on_while_plugged_in（服务端 cleanup 会还原）
     * - 拉长 screen_off_timeout + svc power stayon：无线未充电时官方 stay_awake 无效
     */
    private fun enableStayAwake(dadb: Dadb) {
        try {
            dadb.shell("input keyevent KEYCODE_WAKEUP")
            val old = dadb.shell("settings get system screen_off_timeout").output.trim()
            if (old.isNotEmpty() && old != "null" && old != SCREEN_OFF_TIMEOUT_KEEP_AWAKE) {
                savedScreenOffTimeout = old
            }
            dadb.shell("settings put system screen_off_timeout $SCREEN_OFF_TIMEOUT_KEEP_AWAKE")
            dadb.shell("svc power stayon true")
            stayAwakeApplied = true
            Log.i(TAG, "stay awake enabled, previous screen_off_timeout=$old")
        } catch (e: Exception) {
            Log.w(TAG, "enable stay awake failed: ${e.message}")
        }
    }

    private fun restoreStayAwake(dadb: Dadb?) {
        if (!stayAwakeApplied || dadb == null) return
        try {
            dadb.shell("svc power stayon false")
            val restore = savedScreenOffTimeout
            if (restore != null) {
                dadb.shell("settings put system screen_off_timeout $restore")
                Log.i(TAG, "restored screen_off_timeout=$restore")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore stay awake failed: ${e.message}")
        } finally {
            stayAwakeApplied = false
            savedScreenOffTimeout = null
        }
    }

    private fun startServer(dadb: Dadb) {
        val cmd = buildString {
            append("CLASSPATH=${ScrcpyConstants.SERVER_REMOTE_PATH} app_process / ")
            append("com.genymobile.scrcpy.Server ${ScrcpyConstants.SERVER_VERSION} ")
            append("scid=%08x ".format(scid))
            append("log_level=info ")
            append("audio=false ")
            append("control=true ")
            append("tunnel_forward=true ")
            append("cleanup=true ")
            append("stay_awake=true ")
            append("video_bit_rate=${config.videoBitRate.coerceAtLeast(100_000)} ")
            if (config.videoCodec != VideoCodecOption.H264.serverValue) {
                append("video_codec=${config.videoCodec} ")
            }
            if (config.maxFps > 0) {
                append("max_fps=${config.maxFps} ")
            }
            if (config.lowLatencyEncode) {
                // priority=0 → realtime；部分机型可进一步压编码缓冲
                append("video_codec_options=priority:int=0 ")
            }
            if (maxSize > 0) {
                append("max_size=$maxSize ")
            }
            val newDisplay = config.newDisplay?.trim().orEmpty()
            if (newDisplay.isNotEmpty()) {
                // 虚拟屏尺寸对齐控制端，避免折叠屏主屏比例被拉伸
                append("new_display=$newDisplay ")
                append("keep_active=true ")
                // 默认 IME 会弹到物理主屏；local 才在虚拟屏弹出输入框
                append("display_ime_policy=local")
            }
        }
        Log.i(TAG, "start server: $cmd")
        val shell = dadb.openShell(cmd)
        shellStream = shell

        shellDrainJob = sessionScope.launch {
            val collected = StringBuilder()
            try {
                while (isActive && running.get()) {
                    when (val packet = shell.read()) {
                        is AdbShellPacket.StdOut -> {
                            val text = String(packet.payload)
                            collected.append(text)
                            serverLog.set(collected.toString())
                            Log.i(TAG, "server: ${text.trim()}")
                        }
                        is AdbShellPacket.StdError -> {
                            val text = String(packet.payload)
                            collected.append(text)
                            serverLog.set(collected.toString())
                            Log.w(TAG, "server err: ${text.trim()}")
                        }
                        is AdbShellPacket.Exit -> {
                            val code = packet.payload[0].toInt()
                            collected.append("\nexit=$code")
                            serverLog.set(collected.toString())
                            Log.e(TAG, "server exited: $code")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "shell drain ended: ${e.message}")
                }
            }
        }
    }

    private suspend fun waitForServerReady(timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val log = serverLog.get()
            if (log.contains("Device:")) return
            if (log.contains("exit=")) {
                throw IOException("scrcpy-server 启动失败: ${log.takeLast(200)}")
            }
            delay(50)
        }
        // Continue anyway; accept() may still be ready without flushed logs
        Log.w(TAG, "server ready wait timeout, log=${serverLog.get()}")
    }

    private suspend fun openAbstractSocket(dadb: Dadb, name: String, retries: Int): AdbStream {
        var last: Exception? = null
        repeat(retries) { attempt ->
            val log = serverLog.get()
            if (log.contains("exit=")) {
                throw IOException("scrcpy-server 已退出: ${log.takeLast(200)}")
            }
            try {
                Log.i(TAG, "open localabstract:$name attempt=${attempt + 1}")
                return dadb.open("localabstract:$name")
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "open failed attempt=${attempt + 1}: ${e.message}")
                delay(120L + attempt * 15L)
            }
        }
        val log = serverLog.get().ifBlank { "(no server log)" }
        throw IOException("无法连接 localabstract:$name；server: ${log.takeLast(200)}", last)
    }

    private suspend fun demuxVideo(source: BufferedSource) {
        val header = ByteArray(12)
        try {
            while (running.get()) {
                source.readFully(header)
                val bb = ByteBuffer.wrap(header)
                val ptsAndFlags = bb.long
                if ((ptsAndFlags and (1L shl 63)) != 0L) {
                    val width = ByteBuffer.wrap(header, 4, 4).int
                    val height = ByteBuffer.wrap(header, 8, 4).int
                    decoder.onSessionSize(width, height)
                    withContext(Dispatchers.Main) {
                        listener.onVideoSize(width, height)
                    }
                    continue
                }

                val size = bb.int
                if (size < 0 || size > 16 * 1024 * 1024) {
                    throw IOException("invalid packet size: $size")
                }
                val payload = source.readByteArray(size.toLong())
                val config = (ptsAndFlags and (1L shl 62)) != 0L
                val pts = ptsAndFlags and ((1L shl 61) - 1)
                val arrival = SystemClock.elapsedRealtimeNanos()
                decoder.decode(payload, config, pts, arrival)
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "video demux ended", e)
                stopInternal(e.message)
            }
        }
    }
}
