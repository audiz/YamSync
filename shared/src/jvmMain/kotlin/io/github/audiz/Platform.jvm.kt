package io.github.audiz

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val isMobile: Boolean = false
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

actual fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: ""
                    if (host.isNotBlank()) return host
                }
            }
        }
    } catch (_: Exception) {}
    return "127.0.0.1"
}

actual fun getDeviceName(): String {
    val host = System.getenv("HOSTNAME") ?: System.getenv("COMPUTERNAME")
    if (!host.isNullOrBlank()) return host
    return try {
        java.net.InetAddress.getLocalHost().hostName
    } catch (_: Exception) {
        "PC (${System.getProperty("os.name")})"
    }
}

actual fun generateRandomSessionToken(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(10).lowercase()