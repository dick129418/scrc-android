package com.scrc.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OtgAdbActivator {
    private const val ACTION_USB_PERMISSION = "com.scrc.android.USB_ADB_PERMISSION"
    const val DEFAULT_PORT = 5555

    data class Result(val host: String?, val port: Int)

    suspend fun activate(
        context: Context,
        port: Int = DEFAULT_PORT,
        onStatus: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        onStatus("正在查找 OTG ADB 设备…")
        val found = UsbAdbSession.findAdb(usb)
            ?: throw IOException("未找到 ADB 设备。请用 OTG 连接被控手机，并确认已开 USB 调试")

        val (device, intf) = found
        if (!usb.hasPermission(device)) {
            onStatus("请允许 USB 调试访问权限…")
            requestPermission(app, usb, device)
        }

        // Ensure dadb keys exist, then reuse the same files for USB auth.
        AdbKeyStore.getOrCreate(app)
        val keyDir = File(app.filesDir, ".android")
        val priv = File(keyDir, "adbkey").readBytes()
        val pub = File(keyDir, "adbkey.pub").readBytes()

        onStatus("正在通过 USB 连接 ADB…")
        UsbAdbSession.open(usb, device, intf, priv, pub).use { session ->
            session.connect()
            onStatus("ADB 已连接，正在读取被控 IP…")
            val host = runCatching { detectHost(session) }.getOrNull()
            onStatus("正在开启 tcpip $port …")
            runCatching { session.tcpip(port) }
            Result(host, port)
        }
    }

    private fun detectHost(session: UsbAdbSession): String? {
        val commands = listOf(
            "shell:ip -o -4 addr show scope global",
            "shell:ip -f inet addr show wlan0",
            "shell:getprop dhcp.wlan0.ipaddress",
            "shell:getprop dhcp.eth0.ipaddress",
        )
        for (cmd in commands) {
            val text = String(session.open(cmd))
            parseIpv4(text)?.let { return it }
        }
        return null
    }

    private fun parseIpv4(text: String): String? {
        val re = Regex("""\b((?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
        return re.findAll(text)
            .map { it.value }
            .firstOrNull { !it.startsWith("127.") }
    }

    private suspend fun requestPermission(
        context: Context,
        usb: UsbManager,
        device: UsbDevice,
    ) = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                ctx.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) cont.resume(Unit)
                else cont.resumeWithException(IOException("未授予 USB 权限"))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usb.requestPermission(device, pi)
    }
}
