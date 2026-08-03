package com.scrc.android

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object OtgAdbActivator {
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
        val found = UsbAdb.find(usb)
            ?: throw IOException("未找到 ADB 设备。请用 OTG 连接被控手机，并确认已开 USB 调试")
        val (device, intf) = found
        onStatus("请允许 USB 调试访问权限…")
        UsbAdb.ensurePermission(app, usb, device)
        val keyPair = AdbKeyStore.getOrCreate(app)
        onStatus("正在通过 USB 连接 ADB…")
        UsbAdb.connect(usb, device, intf, keyPair).use { dadb ->
            onStatus("ADB 已连接，正在读取被控 IP…")
            val host = runCatching { detectHost(dadb) }.getOrNull()
            onStatus("正在开启 tcpip $port …")
            runCatching { dadb.open("tcpip:$port").close() }
            Result(host, port)
        }
    }

    private fun detectHost(dadb: dadb.Dadb): String? {
        val commands = listOf(
            "ip -o -4 addr show scope global",
            "ip -f inet addr show wlan0",
            "getprop dhcp.wlan0.ipaddress",
            "getprop dhcp.eth0.ipaddress",
        )
        for (cmd in commands) {
            val text = dadb.shell(cmd).allOutput
            parseIpv4(text)?.let { return it }
        }
        return null
    }

    private fun parseIpv4(text: String): String? {
        val re = Regex("""\b((?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
        return re.findAll(text).map { it.value }.firstOrNull { !it.startsWith("127.") }
    }
}
