package com.scrc.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/** 本机 ↔ 被控剪贴板自动同步，靠 lastSynced 去重防回声。 */
class ClipboardBridge(
    private val context: Context,
    private val sendToRemote: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ClipboardBridge"
    }

    private val cm = context.getSystemService(ClipboardManager::class.java)
    private var lastSynced = ""
    private var active = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!active) return@OnPrimaryClipChangedListener
        val text = readPrimary() ?: return@OnPrimaryClipChangedListener
        if (text == lastSynced) return@OnPrimaryClipChangedListener
        lastSynced = text
        sendToRemote(text)
    }

    fun start() {
        if (active) return
        active = true
        cm.addPrimaryClipChangedListener(listener)
    }

    fun stop() {
        active = false
        cm.removePrimaryClipChangedListener(listener)
    }

    fun setPaused(paused: Boolean) {
        active = !paused
    }

    fun onRemoteText(text: String) {
        if (text == lastSynced) return
        lastSynced = text
        try {
            cm.setPrimaryClip(ClipData.newPlainText("scrcpy", text))
        } catch (e: Exception) {
            Log.w(TAG, "set local clipboard failed: ${e.message}")
        }
    }

    /** 本机 IME 粘贴注入时标记，避免再推一次到远端。 */
    fun noteLocalText(text: String) {
        if (text.isNotEmpty()) lastSynced = text
    }

    private fun readPrimary(): String? =
        try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "read clipboard failed: ${e.message}")
            null
        }
}
