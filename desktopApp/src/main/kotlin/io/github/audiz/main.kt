package io.github.audiz

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

private const val IPC_PORT = 48293

fun main(args: Array<String>) {
    val initialLink = args.firstOrNull { it != "--watchdog" }

    if (args.contains("--watchdog") || System.getProperty("yamsync.watchdog") == "true") {
        saveAppConfig(AppConfigKeys.UI_WATCHDOG_ENABLED, "true")
        io.github.audiz.debug.UiWatchdog.start()
    }

    // 1. Попытка переслать ссылку уже запущенному экземпляру YamSync
    try {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), IPC_PORT)
        socket.use { s ->
            val out = PrintWriter(s.getOutputStream(), true)
            out.println(initialLink ?: "PING")
        }
        // Экземпляр уже запущен — отправляем аргумент и завершаем этот процесс
        exitProcess(0)
    } catch (_: Exception) {
        // Запущенных экземпляров нет — этот процесс становится основным
    }

    // 2. Фоновый поток IPC-сервера для приёма ссылок от других процессов
    val serverThread = Thread {
        try {
            val serverSocket = ServerSocket(IPC_PORT, 50, InetAddress.getByName("127.0.0.1"))
            while (!Thread.currentThread().isInterrupted) {
                val client = serverSocket.accept()
                Thread {
                    try {
                        client.use { s ->
                            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                            val message = reader.readLine()
                            if (!message.isNullOrBlank() && message != "PING") {
                                if (message.startsWith("GET ")) {
                                    val out = PrintWriter(s.getOutputStream(), true)
                                    val html = """
                                        <!DOCTYPE html>
                                        <html>
                                        <head><meta charset="utf-8"><title>YamSync</title></head>
                                        <body style="background:#18181b;color:#f4f4f5;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;margin:0;">
                                          <div style="background:#27272a;padding:28px 40px;border-radius:16px;text-align:center;box-shadow:0 8px 24px rgba(0,0,0,0.4);">
                                            <h2 style="margin:0 0 10px 0;color:#22c55e;">▶️ Воспроизведение в YamSync</h2>
                                            <p style="margin:0;color:#a1a1aa;">Трек отправлен в плеер. Эту вкладку можно закрыть.</p>
                                          </div>
                                          <script>setTimeout(function(){ window.close(); }, 1200);</script>
                                        </body>
                                        </html>
                                    """.trimIndent()
                                    val bytes = html.toByteArray(Charsets.UTF_8)
                                    out.print("HTTP/1.1 200 OK\r\n")
                                    out.print("Content-Type: text/html; charset=UTF-8\r\n")
                                    out.print("Content-Length: ${bytes.size}\r\n")
                                    out.print("Connection: close\r\n\r\n")
                                    out.print(html)
                                    out.flush()
                                }
                                DeepLinkHandler.handleUrl(message)
                            }
                        }
                    } catch (_: Exception) {}
                }.start()
            }
        } catch (e: Exception) {
            println("YamSync IPC Server stopped: ${e.message}")
        }
    }
    serverThread.isDaemon = true
    serverThread.start()

    // Автоматическая регистрация протокола yamsync:// в Linux при необходимости
    registerLinuxDesktopSchemeIfNeeded()

    // 3. Поддержка системного URI Handler (AWT Desktop для macOS / Linux / Windows)
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    val uri = event.uri
                    if (uri != null) {
                        DeepLinkHandler.handleUrl(uri.toString())
                    }
                }
            }
        }
    } catch (_: Throwable) {}

    // 4. Если приложение запущено впервые с аргументом ссылки
    if (!initialLink.isNullOrBlank()) {
        DeepLinkHandler.handleUrl(initialLink)
    }

    // 5. Запуск оконного Compose UI с сохранением и восстановлением размера окна
    val minWidth = 520.dp
    val minHeight = 500.dp
    val defaultWidth = 1100.dp
    val defaultHeight = 780.dp

    val screenSize = try {
        java.awt.Toolkit.getDefaultToolkit().screenSize
    } catch (_: Throwable) {
        null
    }
    val safeMaxWidth = screenSize?.width?.toFloat() ?: 1920f
    val safeMaxHeight = screenSize?.height?.toFloat() ?: 1080f
    val effectiveMaxWidth = maxOf(minWidth.value, safeMaxWidth)
    val effectiveMaxHeight = maxOf(minHeight.value, safeMaxHeight)

    val savedWidth = loadAppConfig(AppConfigKeys.WINDOW_WIDTH)?.toFloatOrNull() ?: defaultWidth.value
    val savedHeight = loadAppConfig(AppConfigKeys.WINDOW_HEIGHT)?.toFloatOrNull() ?: defaultHeight.value
    val isMaximized = loadAppConfig(AppConfigKeys.WINDOW_IS_MAXIMIZED) == "true"

    val initialWidth = savedWidth.coerceIn(minWidth.value, effectiveMaxWidth).dp
    val initialHeight = savedHeight.coerceIn(minHeight.value, effectiveMaxHeight).dp
    val initialPlacement = if (isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating

    application {
        val windowState = rememberWindowState(
            placement = initialPlacement,
            size = DpSize(initialWidth, initialHeight)
        )

        Window(
            state = windowState,
            onCloseRequest = {
                if (windowState.placement == WindowPlacement.Floating) {
                    val w = windowState.size.width.value
                    val h = windowState.size.height.value
                    if (w.isFinite() && h.isFinite() && w >= minWidth.value && h >= minHeight.value) {
                        saveAppConfig(AppConfigKeys.WINDOW_WIDTH, w.toString())
                        saveAppConfig(AppConfigKeys.WINDOW_HEIGHT, h.toString())
                    }
                }
                val isMax = windowState.placement == WindowPlacement.Maximized
                saveAppConfig(AppConfigKeys.WINDOW_IS_MAXIMIZED, isMax.toString())

                exitApplication()
                exitProcess(0)
            },
            title = "YamSync",
            icon = painterResource("icon.png")
        ) {
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(minWidth.value.toInt(), minHeight.value.toInt())
                onDispose { }
            }

            LaunchedEffect(windowState.size, windowState.placement) {
                delay(300)
                if (windowState.placement == WindowPlacement.Floating) {
                    val w = windowState.size.width.value
                    val h = windowState.size.height.value
                    if (w.isFinite() && h.isFinite() && w >= minWidth.value && h >= minHeight.value) {
                        saveAppConfig(AppConfigKeys.WINDOW_WIDTH, w.toString())
                        saveAppConfig(AppConfigKeys.WINDOW_HEIGHT, h.toString())
                    }
                }
                val isMax = windowState.placement == WindowPlacement.Maximized
                saveAppConfig(AppConfigKeys.WINDOW_IS_MAXIMIZED, isMax.toString())
            }

            App()
        }
    }
}

private fun registerLinuxDesktopSchemeIfNeeded() {
    try {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        if (!os.contains("linux")) return

        val userHome = System.getProperty("user.home") ?: return
        val appDir = java.io.File(userHome, ".local/share/applications")
        if (!appDir.exists()) appDir.mkdirs()

        val desktopFile = java.io.File(appDir, "yamsync.desktop")

        // Корректно определяем корневую директорию проекта (если user.dir указывает на desktopApp)
        var projectDir = java.io.File(System.getProperty("user.dir") ?: ".")
        if (projectDir.name == "desktopApp" && projectDir.parentFile != null) {
            projectDir = projectDir.parentFile
        }
        val scriptFile = java.io.File(projectDir, "yamsync")
        if (!scriptFile.exists()) return

        val scriptPath = scriptFile.absolutePath
        val iconPath = java.io.File(projectDir, "desktopApp/icon.png").absolutePath

        val content = """
            [Desktop Entry]
            Name=YamSync
            Comment=YamSync Music Player
            Exec=$scriptPath %u
            Icon=$iconPath
            Terminal=false
            Type=Application
            Categories=Audio;AudioVideo;
            MimeType=x-scheme-handler/yamsync;
        """.trimIndent()

        if (!desktopFile.exists() || desktopFile.readText().trim() != content.trim()) {
            desktopFile.writeText(content)
            desktopFile.setExecutable(true)
            try { ProcessBuilder("update-desktop-database", appDir.absolutePath).start().waitFor() } catch (_: Throwable) {}
            try { ProcessBuilder("xdg-mime", "default", "yamsync.desktop", "x-scheme-handler/yamsync").start().waitFor() } catch (_: Throwable) {}
            try { ProcessBuilder("gio", "mime", "x-scheme-handler/yamsync", "yamsync.desktop").start().waitFor() } catch (_: Throwable) {}
        }
    } catch (_: Throwable) {
        // Игнорируем ошибки при невозможности записи в каталог пользователя
    }
}