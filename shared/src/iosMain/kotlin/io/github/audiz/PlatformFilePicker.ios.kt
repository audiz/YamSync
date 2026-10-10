package io.github.audiz

import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

actual val isPlatformPickerSupported: Boolean = true

actual fun pickDirectory(): String? {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    return paths.firstOrNull() as? String
}

actual fun pickAudioOrPlaylistFile(): String? = null
 
actual fun pickSaveFile(defaultFileName: String, title: String): String? {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val docs = paths.firstOrNull() as? String ?: return null
    return "$docs/$defaultFileName"
}

@Suppress("CONFLICTING_OVERLOADS")
private class IosDocPickerDelegate(
    private val onResult: (String?) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        val path = url?.path
        onResult(path)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}

private var activePickerDelegate: IosDocPickerDelegate? = null

actual fun launchDirectoryPicker(onResult: (String?) -> Unit) {
    val keyWindow = UIApplication.sharedApplication.keyWindow 
        ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
    val rootVc = keyWindow?.rootViewController
    if (rootVc == null) {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        onResult(docs)
        return
    }

    try {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.folder", "public.directory"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen
        )
        val delegate = IosDocPickerDelegate { path ->
            activePickerDelegate = null
            onResult(path)
        }
        activePickerDelegate = delegate
        picker.delegate = delegate
        rootVc.presentViewController(picker, animated = true, completion = null)
    } catch (_: Throwable) {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        onResult(docs)
    }
}

actual fun launchAudioOrPlaylistFilePicker(onResult: (String?) -> Unit) {
    val keyWindow = UIApplication.sharedApplication.keyWindow 
        ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
    val rootVc = keyWindow?.rootViewController
    if (rootVc == null) {
        onResult(null)
        return
    }

    try {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.audio", "public.mp3", "com.apple.m4a-audio", "public.playlist"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen
        )
        val delegate = IosDocPickerDelegate { path ->
            activePickerDelegate = null
            onResult(path)
        }
        activePickerDelegate = delegate
        picker.delegate = delegate
        rootVc.presentViewController(picker, animated = true, completion = null)
    } catch (_: Throwable) {
        onResult(null)
    }
}

actual fun getPlatformPresetDirectories(): List<Pair<String, String>> {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val docs = paths.firstOrNull() as? String ?: return emptyList()
    val fileManager = NSFileManager.defaultManager
    val list = mutableListOf<Pair<String, String>>()
    list.add("📁 Папка Документы" to docs)
    val yamSync = "$docs/YamSync"
    if (fileManager.fileExistsAtPath(yamSync)) {
        list.add("⚡ YamSync" to yamSync)
    }
    val hq = "$docs/HQ"
    if (fileManager.fileExistsAtPath(hq)) {
        list.add("🎧 Папка HQ" to hq)
    }
    return list
}
