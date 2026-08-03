package com.scrc.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

data class HistoryEntry(
    val host: String,
    val port: Int,
    val lastUsedAt: Long,
    val deviceName: String? = null,
    val launchCount: Int = 0,
) {
    val address: String get() = "$host:$port"
}

class ConnectionHistoryStore(private val prefs: SharedPreferences) {
    fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val host = obj.optString("host").trim()
                    val port = obj.optInt("port", -1)
                    if (host.isEmpty() || port !in 1..65535) continue
                    add(
                        HistoryEntry(
                            host = host,
                            port = port,
                            lastUsedAt = obj.optLong("lastUsedAt", 0L),
                            deviceName = obj.optString("deviceName").takeIf { it.isNotBlank() },
                            launchCount = obj.optInt("launchCount", 0).coerceAtLeast(0),
                        ),
                    )
                }
            }.sortedWith(ENTRY_ORDER)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun remember(
        host: String,
        port: Int,
        deviceName: String? = null,
        incrementLaunch: Boolean = true,
    ) {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty() || port !in 1..65535) return

        val now = System.currentTimeMillis()
        val existing = load().toMutableList()
        val index = existing.indexOfFirst { it.host == normalizedHost && it.port == port }
        val previous = if (index >= 0) existing.removeAt(index) else null

        existing.add(
            0,
            HistoryEntry(
                host = normalizedHost,
                port = port,
                lastUsedAt = now,
                deviceName = deviceName?.takeIf { it.isNotBlank() } ?: previous?.deviceName,
                launchCount = (previous?.launchCount ?: 0) + if (incrementLaunch) 1 else 0,
            ),
        )

        persist(existing.take(MAX_ENTRIES))
    }

    fun remove(host: String, port: Int) {
        persist(load().filterNot { it.host == host && it.port == port })
    }

    private fun persist(entries: List<HistoryEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put("host", entry.host)
                    put("port", entry.port)
                    put("lastUsedAt", entry.lastUsedAt)
                    put("launchCount", entry.launchCount)
                    if (!entry.deviceName.isNullOrBlank()) {
                        put("deviceName", entry.deviceName)
                    }
                },
            )
        }
        prefs.edit { putString(KEY_HISTORY, array.toString()) }
    }

    companion object {
        private const val KEY_HISTORY = "connection_history"
        private const val MAX_ENTRIES = 20

        private val NAME_COLLATOR = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }

        private val ENTRY_ORDER = compareByDescending<HistoryEntry> { it.launchCount }
            .thenBy { NAME_COLLATOR.getCollationKey(it.deviceName ?: it.host) }

        fun from(context: Context): ConnectionHistoryStore {
            return ConnectionHistoryStore(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            )
        }

        const val PREFS = "scrc_prefs"
    }
}
