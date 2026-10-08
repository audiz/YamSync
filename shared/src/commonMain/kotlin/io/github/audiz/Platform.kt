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

expect fun getLocalIpAddress(): String

expect fun getDeviceName(): String

expect fun generateRandomSessionToken(): String