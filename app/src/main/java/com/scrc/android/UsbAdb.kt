package com.scrc.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** USB Host 上的完整 ADB 客户端（复用 dadb 协议栈）。 */
object UsbAdb {
    private const val ACTION_USB_PERMISSION = "com.scrc.android.USB_ADB_PERMISSION"
    private const val ADB_CLASS = 0xff
    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01

    fun find(usb: UsbManager): Pair<UsbDevice, UsbInterface>? {
        for (device in usb.deviceList.values) {
            findInterface(device)?.let { return device to it }
        }
        return null
    }

    fun findInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == ADB_CLASS &&
                intf.interfaceSubclass == ADB_SUBCLASS &&
                intf.interfaceProtocol == ADB_PROTOCOL
            ) {
                return intf
            }
        }
        return null
    }

    suspend fun ensurePermission(context: Context, usb: UsbManager, device: UsbDevice) {
        if (usb.hasPermission(device)) return
        suspendCancellableCoroutine { cont ->
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    ctx.unregisterReceiver(this)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(IOException("未授予 USB 权限"))
                    }
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                app,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                flags,
            )
            usb.requestPermission(device, pi)
        }
    }

    fun connect(usb: UsbManager, device: UsbDevice, intf: UsbInterface, keyPair: AdbKeyPair): Dadb {
        val conn = usb.openDevice(device) ?: throw IOException("无法打开 USB 设备")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("无法占用 ADB 接口")
        }
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) {
            conn.releaseInterface(intf)
            conn.close()
            throw IOException("未找到 ADB bulk 端点")
        }
        val pipe = UsbBulkPipe(conn, intf, epIn, epOut)
        return UsbDadb(connectAdb(pipe.source(), pipe.sink(), keyPair, pipe))
    }

    /** 打开已授权的 ADB 设备；没有则抛错。 */
    fun connectReady(context: Context, keyPair: AdbKeyPair): Dadb {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val found = find(usb) ?: throw IOException("未找到 OTG ADB 设备")
        val (device, intf) = found
        if (!usb.hasPermission(device)) throw IOException("没有 USB 权限，请先授权")
        return connect(usb, device, intf, keyPair)
    }

    private fun connectAdb(
        source: Source,
        sink: Sink,
        keyPair: AdbKeyPair,
        closeable: Closeable,
    ): Any {
        val companion = Class.forName("dadb.AdbConnection")
            .getDeclaredField("Companion")
            .get(null)
        val method = companion.javaClass.getDeclaredMethod(
            "connect",
            Source::class.java,
            Sink::class.java,
            AdbKeyPair::class.java,
            Closeable::class.java,
        )
        method.isAccessible = true
        return try {
            method.invoke(companion, source, sink, keyPair, closeable)
                ?: throw IOException("ADB USB 握手失败")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause as? Exception) ?: IOException("ADB USB 握手失败", e)
        }
    }

    private class UsbDadb(private val connection: Any) : Dadb {
        private val openMethod = connection.javaClass.getMethod("open", String::class.java)
        private val featureMethod =
            connection.javaClass.getMethod("supportsFeature", String::class.java)

        override fun open(destination: String): AdbStream = invoke {
            openMethod.invoke(connection, destination) as AdbStream
        }

        override fun supportsFeature(feature: String): Boolean = invoke {
            featureMethod.invoke(connection, feature) as Boolean
        }

        override fun close() {
            runCatching { (connection as AutoCloseable).close() }
        }

        private inline fun <T> invoke(block: () -> T): T {
            return try {
                block()
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.cause as? Exception) ?: e
            }
        }
    }

    private class UsbBulkPipe(
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val epIn: UsbEndpoint,
        private val epOut: UsbEndpoint,
    ) : Closeable {
        @Volatile private var closed = false
        private val readLock = Any()
        private val writeLock = Any()

        fun source(): Source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                val size = min(byteCount, 16_384L).toInt()
                val buf = ByteArray(size)
                val n = read(buf)
                if (n < 0) return -1
                sink.write(buf, 0, n)
                return n.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }

        fun sink(): Sink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                var left = byteCount
                while (left > 0) {
                    val size = min(left, 16_384L).toInt()
                    val buf = ByteArray(size)
                    val read = source.read(buf)
                    if (read <= 0) throw IOException("USB sink underflow")
                    write(buf, read)
                    left -= read
                }
            }

            override fun flush() = Unit
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }

        private fun read(buf: ByteArray): Int {
            synchronized(readLock) {
                if (closed) return -1
                val n = connection.bulkTransfer(epIn, buf, buf.size, 0)
                if (n < 0) {
                    if (closed) return -1
                    throw IOException("USB read failed ($n)")
                }
                return n
            }
        }

        private fun write(buf: ByteArray, length: Int) {
            synchronized(writeLock) {
                if (closed) throw IOException("USB closed")
                var offset = 0
                var left = length
                val tmp = ByteArray(left)
                System.arraycopy(buf, 0, tmp, 0, left)
                while (left > 0) {
                    val n = connection.bulkTransfer(epOut, tmp, left, 5_000)
                    if (n <= 0) throw IOException("USB write failed ($n)")
                    offset += n
                    left -= n
                    if (left > 0) System.arraycopy(buf, offset, tmp, 0, left)
                }
            }
        }

        override fun close() {
            closed = true
            runCatching { connection.releaseInterface(intf) }
            connection.close()
        }
    }
}
