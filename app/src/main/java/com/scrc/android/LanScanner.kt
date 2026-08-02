package com.scrc.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(
    val host: String,
    val port: Int,
)

object LanScanner {
    private const val CONNECT_TIMEOUT_MS = 250
    private const val BATCH_SIZE = 64

    /** 本机 IPv4 及所属 /24 前缀（如 192.168.43），覆盖 Wi‑Fi 与手机热点网卡。 */
    fun localSubnets(): List<LocalSubnet> {
        val result = linkedMapOf<String, LocalSubnet>()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            emptyList()
        }
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in Collections.list(nif.inetAddresses)) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.startsWith("169.254.")) continue // 链路本地
                val parts = host.split('.')
                if (parts.size != 4) continue
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                result.putIfAbsent(prefix, LocalSubnet(prefix, host))
            }
        }
        return result.values.toList()
    }

    /**
     * 扫描各 /24 网段上 [ports] 是否开放（TCP connect）。
     * @param onProgress scanned / total
     * @param onFound 每发现一台回调一次（主调用方可切到主线程更新 UI）
     */
    suspend fun scan(
        ports: Set<Int>,
        onProgress: suspend (scanned: Int, total: Int) -> Unit = { _, _ -> },
        onFound: suspend (DiscoveredDevice) -> Unit = {},
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val uniquePorts = ports.filter { it in 1..65535 }.toSet()
        if (uniquePorts.isEmpty()) return@withContext emptyList()

        val subnets = localSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()

        val selfHosts = subnets.map { it.selfHost }.toSet()
        val targets = buildList {
            for (subnet in subnets) {
                for (last in 1..254) {
                    val host = "${subnet.prefix}.$last"
                    if (host in selfHosts) continue
                    for (port in uniquePorts) {
                        add(host to port)
                    }
                }
            }
        }

        val total = targets.size
        val scanned = AtomicInteger(0)
        val foundMutex = Mutex()
        val found = linkedSetOf<DiscoveredDevice>()

        coroutineScope {
            targets.chunked(BATCH_SIZE).forEach { batch ->
                batch.map { (host, port) ->
                    async {
                        val open = isPortOpen(host, port)
                        val done = scanned.incrementAndGet()
                        if (done % 16 == 0 || done == total) {
                            onProgress(done, total)
                        }
                        if (open) {
                            val device = DiscoveredDevice(host, port)
                            foundMutex.withLock {
                                if (found.add(device)) {
                                    onFound(device)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        found.toList()
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    data class LocalSubnet(
        val prefix: String,
        val selfHost: String,
    )
}
