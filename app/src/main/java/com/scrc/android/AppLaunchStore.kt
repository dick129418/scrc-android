package com.scrc.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/** 独立应用模式：按包名累计启动次数，列表优先常用。 */
class AppLaunchStore(private val prefs: SharedPreferences) {
    fun remember(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val map = loadMap()
        map[pkg] = (map[pkg] ?: 0) + 1
        persist(map)
    }

    fun sort(apps: List<DeviceApp>): List<DeviceApp> {
        val counts = loadMap()
        val collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
        return apps.sortedWith(
            compareByDescending<DeviceApp> { counts[it.packageName] ?: 0 }
                .thenBy { collator.getCollationKey(it.name) }
                .thenBy { it.packageName },
        )
    }

    private fun loadMap(): MutableMap<String, Int> {
        val raw = prefs.getString(KEY_APP_LAUNCH, null) ?: return mutableMapOf()
        val obj = JSONObject(raw)
        val map = mutableMapOf<String, Int>()
        for (key in obj.keys()) {
            map[key] = obj.optInt(key, 0).coerceAtLeast(0)
        }
        return map
    }

    private fun persist(map: Map<String, Int>) {
        val obj = JSONObject()
        for ((pkg, count) in map) {
            if (count > 0) obj.put(pkg, count)
        }
        prefs.edit { putString(KEY_APP_LAUNCH, obj.toString()) }
    }

    companion object {
        private const val KEY_APP_LAUNCH = "app_launch_counts"

        fun from(context: Context): AppLaunchStore =
            AppLaunchStore(
                context.getSharedPreferences(ConnectionHistoryStore.PREFS, Context.MODE_PRIVATE),
            )
    }
}
