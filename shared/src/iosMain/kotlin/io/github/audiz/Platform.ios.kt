package io.github.audiz

class IOSPlatform: Platform {
    override val name: String = "iOS"
    override val isMobile: Boolean = true
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun currentTimeMillis(): Long = io.ktor.util.date.getTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = block()

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun getLocalIpAddress(): String {
    var ip = "127.0.0.1"
    kotlinx.cinterop.memScoped {
        val ifap = kotlinx.cinterop.allocPointerTo<platform.posix.ifaddrs>()
        if (platform.posix.getifaddrs(ifap.ptr) == 0) {
            var curr = ifap.value
            while (curr != null) {
                val ifa = curr.pointed
                val addr = ifa.ifa_addr
                if (addr != null && addr.pointed.sa_family == platform.posix.AF_INET.toUByte()) {
                    val name = ifa.ifa_name?.let { kotlinx.cinterop.toKString(it) } ?: ""
                    if (name == "en0" || name.startsWith("en")) {
                        val sin = addr.reinterpret<platform.posix.sockaddr_in>().pointed
                        val ipBuffer = allocArray<kotlinx.cinterop.ByteVar>(platform.posix.INET_ADDRSTRLEN)
                        platform.posix.inet_ntop(platform.posix.AF_INET, sin.sin_addr.ptr, ipBuffer, platform.posix.INET_ADDRSTRLEN.toUInt())
                        ip = kotlinx.cinterop.toKString(ipBuffer)
                        if (name == "en0") break
                    }
                }
                curr = ifa.ifa_next
            }
            platform.posix.freeifaddrs(ifap.value)
        }
    }
    return ip
}

actual fun getDeviceName(): String = platform.UIKit.UIDevice.currentDevice.name

actual fun generateRandomSessionToken(): String =
    platform.Foundation.NSUUID.UUID().UUIDString.replace("-", "").take(10).lowercase()



