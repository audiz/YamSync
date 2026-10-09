package io.github.audiz

fun interface QrScanCallback {
    fun onScanned(code: String)
}

interface IosQrScannerProvider {
    fun launchQrScanner(callback: QrScanCallback)
}

object IosQrScannerRegistry {
    var provider: IosQrScannerProvider? = null

    fun register(provider: IosQrScannerProvider) {
        this.provider = provider
    }
}

actual val isPlatformQrScannerSupported: Boolean
    get() = true

actual fun launchPlatformQrScanner(onScanned: (String) -> Unit) {
    val provider = IosQrScannerRegistry.provider
    if (provider != null) {
        provider.launchQrScanner { scanned ->
            onScanned(scanned)
        }
    } else {
        println("⚠️ IosQrScanner: IosQrScannerProvider не зарегистрирован")
    }
}
