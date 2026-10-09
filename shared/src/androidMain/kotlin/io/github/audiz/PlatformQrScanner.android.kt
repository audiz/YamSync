package io.github.audiz

actual val isPlatformQrScannerSupported: Boolean = false

actual fun launchPlatformQrScanner(onScanned: (String) -> Unit) {
    // Резерв для Android сканера
}
