package com.scrc.android

import android.content.Context
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

object RemoteAppLister {
    private const val TAG = "RemoteAppLister"

    suspend fun listApps(context: Context, host: String, port: Int): List<DeviceApp> =
        withContext(Dispatchers.IO) {
            val keyPair = AdbKeyStore.getOrCreate(context)
            Dadb.create(host, port, keyPair, connectTimeout = 10_000, socketTimeout = 60_000).use { dadb ->
                listApps(context, dadb)
            }
        }

    suspend fun listAppsUsb(context: Context): List<DeviceApp> =
        withContext(Dispatchers.IO) {
            val keyPair = AdbKeyStore.getOrCreate(context)
            UsbAdb.connectReady(context, keyPair).use { dadb ->
                listApps(context, dadb)
            }
        }

    private fun listApps(context: Context, dadb: Dadb): List<DeviceApp> {
        ScrcpyServerFiles.push(context, dadb)
        // cleanup=false：list 模式若 cleanup=true 会删除 jar，影响后续投屏
        val cmd = buildString {
            append("CLASSPATH=${ScrcpyConstants.SERVER_REMOTE_PATH} app_process / ")
            append("com.genymobile.scrcpy.Server ${ScrcpyConstants.SERVER_VERSION} ")
            append("list_apps=true cleanup=false")
        }
        Log.i(TAG, "list apps: $cmd")
        val text = dadb.shell(cmd).allOutput
        Log.i(TAG, "list apps output length=${text.length}")
        val apps = parse(text)
        if (apps.isEmpty()) {
            throw IllegalStateException(
                "未解析到应用列表。输出：${text.takeLast(240).replace('\n', ' ')}",
            )
        }
        return sortByName(apps)
    }

    fun sortByName(apps: List<DeviceApp>): List<DeviceApp> {
        val collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
        return apps.sortedWith { a, b ->
            val byName = collator.compare(a.name, b.name)
            if (byName != 0) byName else a.packageName.compareTo(b.packageName)
        }
    }

    internal fun parse(log: String): List<DeviceApp> {
        val apps = ArrayList<DeviceApp>()
        val lines = log.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val header = APP_LINE.matchEntire(line)
            if (header == null) {
                i++
                continue
            }
            val system = header.groupValues[1] == "*"
            val rest = header.groupValues[2]
            val sameLine = SAME_LINE_PKG.matchEntire(rest)
            if (sameLine != null) {
                apps += DeviceApp(
                    name = sameLine.groupValues[1].trim(),
                    packageName = sameLine.groupValues[2],
                    system = system,
                )
            } else {
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (PACKAGE_NAME.matches(next)) {
                    apps += DeviceApp(
                        name = rest.trim(),
                        packageName = next,
                        system = system,
                    )
                    i++
                }
            }
            i++
        }
        return apps.distinctBy { it.packageName }
    }

    private val APP_LINE = Regex("""^ ([*-]) (.+)$""")
    private val SAME_LINE_PKG = Regex("""^(.+?)\s{2,}([\w.]+)$""")
    private val PACKAGE_NAME = Regex("""^[\w.]+$""")
}
