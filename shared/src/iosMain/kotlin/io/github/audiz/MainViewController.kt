package io.github.audiz

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
fun MainViewController(): UIViewController {
    setUnhandledExceptionHook { throwable ->
        val stack = throwable.stackTraceToString()
        // 1. Безопасный прямой вывод в stderr через POSIX fprintf (без форматирования Objective-C и PAC-ловушек)
        platform.posix.fprintf(platform.posix.stderr, "💥 CRITICAL KOTLIN/NATIVE EXCEPTION:\n%s\n", stack)

        // 2. Сохраняем в файл Documents/last_crash.txt, доступный через приложение «Файлы»
        try {
            val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            if (docs != null) {
                val path = "$docs/last_crash.txt"
                val data = NSString.create(string = stack).dataUsingEncoding(NSUTF8StringEncoding)
                data?.writeToFile(path, atomically = true)
            }
        } catch (_: Throwable) {}
    }
    return ComposeUIViewController(configure = {
        enforceStrictPlistSanityCheck = false
    }) { App() }
}

fun registerIosTrackSigner(provider: IosTrackSignerProvider) {
    IosTrackSignerRegistry.register(provider)
}

fun registerAudioTapAttacher(attacher: IosAudioTapAttacher) {
    IosAudioTapRegistry.register(attacher)
}

fun registerEqualizerListener(listener: IosEqualizerListener) {
    IosEqualizerBridge.register(listener)
}

fun registerQrScannerProvider(provider: IosQrScannerProvider) {
    IosQrScannerRegistry.register(provider)
}


