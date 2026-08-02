package com.scrc.android

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyStore {
    fun getOrCreate(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, ".android")
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
