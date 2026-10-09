@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.audiz

import kotlinx.cinterop.*
import platform.darwin.*
import platform.posix.*

class IOSPlatform: Platform {
    override val name: String = "iOS"
    override val isMobile: Boolean = true
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun currentTimeMillis(): Long = io.ktor.util.date.getTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = block()

actual fun getLocalIpAddress(): String {
    var ip = "127.0.0.1"
    memScoped {
        val ifap = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(ifap.ptr) == 0) {
            var curr = ifap.value
            while (curr != null) {
                val ifa = curr.pointed
                val addr = ifa.ifa_addr
                if (addr != null && addr.pointed.sa_family == AF_INET.toUByte()) {
                    val name = ifa.ifa_name?.toKString() ?: ""
                    if (name == "en0" || name.startsWith("en")) {
                        val sin = addr.reinterpret<sockaddr_in>().pointed
                        val ipBuffer = allocArray<kotlinx.cinterop.ByteVar>(INET_ADDRSTRLEN)
                        inet_ntop(AF_INET, sin.sin_addr.ptr, ipBuffer, INET_ADDRSTRLEN.toUInt())
                        ip = ipBuffer.toKString()
                        if (name == "en0") break
                    }
                }
                curr = ifa.ifa_next
            }
            freeifaddrs(ifap.value)
        }
    }
    return ip
}

actual fun getDeviceName(): String = platform.UIKit.UIDevice.currentDevice.name

actual fun generateRandomSessionToken(): String =
    platform.Foundation.NSUUID.UUID().UUIDString.replace("-", "").take(10).lowercase()



