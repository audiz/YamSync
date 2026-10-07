package io.github.audiz

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun saveFilePlatformSpecific(bytes: ByteArray, fileName: String) {
    val musicDir = getDefaultMusicDir()
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(musicDir)) {
        fileManager.createDirectoryAtPath(musicDir, withIntermediateDirectories = true, attributes = null, error = null)
    }
    val filePath = "$musicDir/$fileName"
    bytes.usePinned { pinned ->
        val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        data.writeToFile(filePath, atomically = true)
    }
    try {
        val url = NSURL.fileURLWithPath(filePath)
        url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    } catch (_: Throwable) {}
}

actual fun saveSessionToken(token: String) {
    saveAppConfig("session_cookie", token)
}

actual fun loadSavedSessionToken(): String? {
    return loadAppConfig("session_cookie")
}

actual fun saveAppConfig(key: String, value: String) {
    NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
}

actual fun loadAppConfig(key: String): String? {
    return NSUserDefaults.standardUserDefaults.stringForKey(key)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun getFileSize(filePath: String): Long {
    val fileManager = platform.Foundation.NSFileManager.defaultManager
    val attributes = fileManager.attributesOfItemAtPath(filePath, null)
    val sizeNumber = attributes?.get(platform.Foundation.NSFileSize) as? platform.Foundation.NSNumber
    return sizeNumber?.longValue ?: 0L
}
