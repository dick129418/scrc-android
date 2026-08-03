package com.scrc.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Minimal ADB-over-USB session for one-shot services (shell / tcpip).
 * Reuses the same ~/.android-style keys as [AdbKeyStore] / dadb.
 */
class UsbAdbSession private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val privateKey: PrivateKey,
    private val publicKeyPayload: ByteArray,
) : Closeable {
    private var localId = 0
    private var sentSignature = false

    fun connect() {
        writePacket(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)
        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_AUTH -> {
                    if (msg.arg0 != AUTH_TYPE_TOKEN) continue
                    if (sentSignature) {
                        writePacket(CMD_AUTH, AUTH_TYPE_RSA_PUBLIC, 0, publicKeyPayload)
                    } else {
                        writePacket(CMD_AUTH, AUTH_TYPE_SIGNATURE, 0, sign(msg.payload))
                        sentSignature = true
                    }
                }
                CMD_CNXN -> return
            }
        }
    }

    /** Open a service; collect WRTE payloads until CLSE. */
    fun open(destination: String): ByteArray {
        val id = ++localId
        writePacket(CMD_OPEN, id, 0, (destination + "\u0000").toByteArray())
        var remoteId = 0
        val out = ByteArrayOutputStream()
        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> if (msg.arg1 == id) remoteId = msg.arg0
                CMD_WRTE -> if (msg.arg1 == id) {
                    out.write(msg.payload)
                    writePacket(CMD_OKAY, id, remoteId, null)
                }
                CMD_CLSE -> if (msg.arg1 == id) {
                    writePacket(CMD_CLSE, id, remoteId, null)
                    return out.toByteArray()
                }
            }
        }
    }

    /** tcpip may tear down USB; treat disconnect after OPEN as success. */
    fun tcpip(port: Int) {
        val id = ++localId
        writePacket(CMD_OPEN, id, 0, "tcpip:$port\u0000".toByteArray())
        while (true) {
            val msg = runCatching { readPacket() }.getOrNull() ?: return
            when (msg.command) {
                CMD_OKAY -> if (msg.arg1 == id) continue
                CMD_CLSE -> if (msg.arg1 == id) return
                CMD_WRTE -> if (msg.arg1 == id) writePacket(CMD_OKAY, id, msg.arg0, null)
            }
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        connection.close()
    }

    private fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    private fun writePacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val length = payload?.size ?: 0
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(length)
        header.putInt(if (payload == null) 0 else checksum(payload))
        header.putInt(command xor -1)
        writeFully(header.array())
        if (payload != null && payload.isNotEmpty()) writeFully(payload)
    }

    private fun readPacket(): AdbPacket {
        val header = ByteArray(24)
        readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        val sum = buf.int
        val magic = buf.int
        if (command != (magic xor -1)) throw IOException("bad ADB magic")
        val payload = if (length > 0) {
            val p = ByteArray(length)
            readFully(p)
            if (checksum(p) != sum) throw IOException("bad ADB checksum")
            p
        } else {
            ByteArray(0)
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun writeFully(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = connection.bulkTransfer(epOut, data, offset, data.size - offset, TIMEOUT_MS)
            if (n <= 0) throw IOException("USB write failed ($n)")
            offset += n
        }
    }

    private fun readFully(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = connection.bulkTransfer(epIn, data, offset, data.size - offset, TIMEOUT_MS)
            if (n <= 0) throw IOException("USB read failed ($n)")
            offset += n
        }
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    companion object {
        /** 需覆盖被控端「允许 USB 调试」弹窗的等待时间 */
        private const val TIMEOUT_MS = 60_000
        private const val ADB_CLASS = 0xff
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257
        private const val AUTH_TYPE_TOKEN = 1
        private const val AUTH_TYPE_SIGNATURE = 2
        private const val AUTH_TYPE_RSA_PUBLIC = 3
        private const val CONNECT_VERSION = 0x01000000
        private const val CONNECT_MAXDATA = 4096
        private val CONNECT_PAYLOAD = "host::\u0000".toByteArray()

        // 256-byte RSA block = padding(236) + SHA1(20); 0xff count = 218
        private val SIGNATURE_PADDING = byteArrayOf(
            0x00, 0x01,
            *ByteArray(218) { 0xff.toByte() },
            0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun findAdb(usb: UsbManager): Pair<UsbDevice, UsbInterface>? {
            for (device in usb.deviceList.values) {
                findAdbInterface(device)?.let { return device to it }
            }
            return null
        }

        fun findAdbInterface(device: UsbDevice): UsbInterface? {
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

        fun open(
            usb: UsbManager,
            device: UsbDevice,
            intf: UsbInterface,
            privateKeyPem: ByteArray,
            publicKeyFileBytes: ByteArray,
        ): UsbAdbSession {
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
            return UsbAdbSession(
                connection = conn,
                intf = intf,
                epIn = epIn,
                epOut = epOut,
                privateKey = parsePrivateKey(privateKeyPem),
                publicKeyPayload = publicKeyPayload(publicKeyFileBytes),
            )
        }

        private fun parsePrivateKey(pem: ByteArray): PrivateKey {
            val text = String(pem)
            val b64 = text
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64))
            return KeyFactory.getInstance("RSA").generatePrivate(spec)
        }

        private fun publicKeyPayload(pubFile: ByteArray): ByteArray {
            val withNul = ByteArray(pubFile.size + 1)
            System.arraycopy(pubFile, 0, withNul, 0, pubFile.size)
            return withNul
        }

        private fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) sum += b.toInt() and 0xff
            return sum
        }
    }
}
