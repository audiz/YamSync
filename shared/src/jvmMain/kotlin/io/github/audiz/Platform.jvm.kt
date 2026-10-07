package io.github.audiz

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val isMobile: Boolean = false
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)