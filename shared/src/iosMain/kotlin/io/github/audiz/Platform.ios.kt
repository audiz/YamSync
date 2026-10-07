package io.github.audiz

class IOSPlatform: Platform {
    override val name: String = "iOS"
    override val isMobile: Boolean = true
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun currentTimeMillis(): Long = io.ktor.util.date.getTimeMillis()

actual val DispatcherIO: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = block()


