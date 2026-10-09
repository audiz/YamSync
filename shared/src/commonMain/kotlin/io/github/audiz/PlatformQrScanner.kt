package io.github.audiz

/**
 * 📷 Поддержка сканирования QR-кодов камерой устройства (iOS / Android)
 */
expect val isPlatformQrScannerSupported: Boolean

/**
 * Запуск системного сканера QR-кодов.
 * При успешном распознавании вызывается [onScanned] со считанной строкой URI.
 */
expect fun launchPlatformQrScanner(onScanned: (String) -> Unit)
