package io.github.audiz

import kotlinx.coroutines.CoroutineDispatcher

interface Platform {
    val name: String
    val isMobile: Boolean
}

expect fun getPlatform(): Platform

expect fun currentTimeMillis(): Long

expect val DispatcherIO: CoroutineDispatcher

expect inline fun <R> synchronized(lock: Any, block: () -> R): R