package com.scrc.android

import android.content.Context
import dadb.Dadb
import okio.buffer
import okio.source
import java.io.File

object ScrcpyServerFiles {
    private const val PUSH_MODE = 420 // 0644

    fun push(context: Context, dadb: Dadb) {
        val tmp = File(context.cacheDir, ScrcpyConstants.SERVER_ASSET)
        context.assets.open(ScrcpyConstants.SERVER_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        tmp.source().buffer().use { source ->
            dadb.push(
                source,
                ScrcpyConstants.SERVER_REMOTE_PATH,
                PUSH_MODE,
                System.currentTimeMillis(),
            )
        }
    }
}
