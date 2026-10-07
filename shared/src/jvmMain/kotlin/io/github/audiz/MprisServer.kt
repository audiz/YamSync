package io.github.audiz

import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 🐧 MPRIS 2.2 D-Bus Сервер для интеграции с системным звуковым виджетом Linux
 * (Cinnamon Sound Applet, GNOME Quick Settings, KDE Media Controller, lock screen, playerctl, media keys)
 */
class MprisServer(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onTogglePlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onVolumeChanged: ((Float) -> Unit)? = null
) {
    @Volatile
    private var isRunning = false
    @Volatile
    private var socketChannel: SocketChannel? = null
    private val serialCounter = AtomicInteger(1)

    // Текущее состояние медиаплеера
    @Volatile private var trackTitle = "YamSync"
    @Volatile private var artistName = ""
    @Volatile private var albumName = ""
    @Volatile private var durationMicrosec = 0L
    @Volatile private var positionMicrosec = 0L
    @Volatile private var playbackStatus = "Stopped" // "Playing", "Paused", "Stopped"
    @Volatile private var trackId = "0"
    @Volatile private var volume = 1.0f
    @Volatile private var loopStatus = "None" // "None", "Track", "Playlist"
    @Volatile private var shuffle = false
    @Volatile private var artUrl = ""
    private val desktopEntryName: String by lazy { resolveDesktopEntry() }

    private fun resolveDesktopEntry(): String {
        try {
            val userHome = System.getProperty("user.home") ?: ""
            val candidateNames = listOf("yamsync", "YamSync", "YaMusicD", "yamusic", "io.github.audiz.YandexMusicDownloader")
            for (name in candidateNames) {
                val p1 = Path.of(userHome, ".local/share/applications/$name.desktop")
                if (Files.exists(p1)) return name
                val p2 = Path.of("/usr/share/applications/$name.desktop")
                if (Files.exists(p2)) return name
            }
        } catch (_: Exception) {}
        return "yamsync"
    }

    private val logFilePath = Path.of("/tmp/mpris_debug.log")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private fun log(msg: String, throwable: Throwable? = null) {
        val time = try { LocalTime.now().format(timeFormatter) } catch (_: Exception) { "" }
        val line = "[$time] 🐧 MPRIS: $msg"
        println(line)
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println(line)
            throwable?.printStackTrace(pw)
            Files.writeString(
                logFilePath,
                sw.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (_: Exception) {}
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        log("Запуск сервиса MPRIS D-Bus...")

        Thread {
            runLoop()
        }.apply {
            isDaemon = true
            name = "MPRIS-DBus-Thread"
            start()
        }
    }

    fun stop() {
        log("Остановка сервиса MPRIS D-Bus")
        isRunning = false
        cleanupSocket()
    }

    private fun cleanupSocket() {
        val channel = socketChannel
        socketChannel = null
        if (channel != null) {
            try {
                channel.close()
            } catch (_: Exception) {}
        }
    }

    fun updateVolume(vol: Float) {
        this.volume = vol.coerceIn(0f, 1f)
    }

    fun updateMetadata(title: String, artist: String, album: String, durationMs: Long, id: String?, artUrl: String? = null) {
        this.trackTitle = title.ifBlank { "Unknown Track" }
        this.artistName = artist.ifBlank { "Unknown Artist" }
        this.albumName = album
        this.durationMicrosec = (durationMs.coerceAtLeast(0)) * 1000L
        this.trackId = id ?: "0"
        this.artUrl = artUrl ?: ""
        sendPropertiesChanged()
    }

    fun updatePlaybackState(isPlaying: Boolean, isPaused: Boolean, positionMs: Long) {
        this.positionMicrosec = (positionMs.coerceAtLeast(0)) * 1000L
        val newStatus = when {
            isPlaying && !isPaused -> "Playing"
            isPaused -> "Paused"
            else -> "Stopped"
        }
        val statusChanged = (newStatus != this.playbackStatus)
        this.playbackStatus = newStatus
        if (statusChanged) {
            sendPropertiesChanged()
        }
    }

    private fun runLoop() {
        while (isRunning) {
            try {
                connectAndHandle()
            } catch (t: Throwable) {
                log("Исключение в основном цикле D-Bus: ${t.message}", t)
            } finally {
                cleanupSocket()
            }

            if (isRunning) {
                log("Соединение разорвано. Повторное подключение через 3 секунды...")
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        log("Поток MPRIS D-Bus завершил работу")
    }

    private fun connectAndHandle() {
        val socketPath = resolveDBusAddress()
        if (socketPath == null) {
            val uid = getUnixUid()
            val env = System.getenv("DBUS_SESSION_BUS_ADDRESS")
            log("Сокет D-Bus не найден (DBUS_SESSION_BUS_ADDRESS='$env', проверен /run/user/$uid/bus)")
            return
        }

        log("Подключение к D-Bus через сокет: $socketPath ...")
        val address = UnixDomainSocketAddress.of(socketPath)
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(address)
        this.socketChannel = channel
        log("Сокет успешно подключен к D-Bus")

        // 1. D-Bus SASL аутентификация
        val uid = getUnixUid()
        val uidHex = uid.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        val authMsg = "\u0000AUTH EXTERNAL $uidHex\r\n"
        log("Отправка SASL AUTH EXTERNAL (uid=$uid, hex=$uidHex)")
        channel.write(ByteBuffer.wrap(authMsg.toByteArray(Charsets.US_ASCII)))

        val authResp = readAsciiLine(channel)
        log("Ответ SASL: '$authResp'")
        if (!authResp.startsWith("OK")) {
            log("Ошибка SASL аутентификации D-Bus: $authResp")
            return
        }

        channel.write(ByteBuffer.wrap("BEGIN\r\n".toByteArray(Charsets.US_ASCII)))
        log("SASL аутентификация успешно завершена (BEGIN отправлен)")

        // 2. Регистрация в D-Bus: Hello
        val helloSerial = serialCounter.getAndIncrement()
        log("Отправка метода org.freedesktop.DBus.Hello (serial=$helloSerial)")
        sendMethodCall(
            channel = channel,
            serial = helloSerial,
            destination = "org.freedesktop.DBus",
            path = "/org/freedesktop/DBus",
            iface = "org.freedesktop.DBus",
            member = "Hello",
            signature = "",
            body = byteArrayOf()
        )

        // Синхронно ожидаем ответ на Hello
        var helloReply: DbusMessage? = null
        while (channel.isOpen) {
            val msg = readDbusMessage(channel) ?: break
            if (msg.replySerial == helloSerial) {
                helloReply = msg
                break
            }
        }
        if (helloReply == null) {
            log("Не получен ответ на Hello от D-Bus (сокет закрыт)")
            return
        }
        if (helloReply.type == 3) {
            log("D-Bus вернул ошибку на Hello: ${helloReply.errorName} - ${helloReply.readStringBody()}")
            return
        }
        val assignedName = helloReply.readStringBody()
        log("Получен ответ на Hello! Назначен уникальный bus name: '$assignedName'")

        // 3. Запрос имени org.mpris.MediaPlayer2.YandexMusicDownloader
        // Флаги: 1 (ALLOW_REPLACEMENT) | 2 (REPLACE_EXISTING) | 4 (DO_NOT_QUEUE) = 7
        val reqSerial = serialCounter.getAndIncrement()
        val busName = "org.mpris.MediaPlayer2.YandexMusicDownloader"
        val reqBody = DbusWriter().apply {
            writeString(busName)
            writeUInt32(7)
        }.toByteArray()

        log("Отправка RequestName('$busName', flags=7, serial=$reqSerial)")
        sendMethodCall(
            channel = channel,
            serial = reqSerial,
            destination = "org.freedesktop.DBus",
            path = "/org/freedesktop/DBus",
            iface = "org.freedesktop.DBus",
            member = "RequestName",
            signature = "su",
            body = reqBody
        )

        // Синхронно ожидаем ответ на RequestName
        var reqReply: DbusMessage? = null
        while (channel.isOpen) {
            val msg = readDbusMessage(channel) ?: break
            if (msg.replySerial == reqSerial) {
                reqReply = msg
                break
            }
        }
        if (reqReply == null) {
            log("Не получен ответ на RequestName от D-Bus")
            return
        }
        if (reqReply.type == 3) {
            log("D-Bus вернул ошибку на RequestName: ${reqReply.errorName} - ${reqReply.readStringBody()}")
            return
        }
        val resultCode = if (reqReply.body.size >= 4) {
            ByteBuffer.wrap(reqReply.body).order(ByteOrder.LITTLE_ENDIAN).int
        } else -1

        // 1 = PRIMARY_OWNER, 4 = ALREADY_OWNER
        if (resultCode != 1 && resultCode != 4) {
            val desc = when (resultCode) {
                2 -> "IN_QUEUE"
                3 -> "EXISTS (имя уже занято другим процессом)"
                else -> "UNKNOWN ($resultCode)"
            }
            log("Ошибка регистрации имени $busName: код $resultCode ($desc)")
            return
        }

        log("Успешно зарегистрирован на D-Bus как $busName (код: $resultCode)")

        // Если уже есть трек или статус не Stopped, сразу публикуем свойства
        if (trackTitle != "Yandex Music" || playbackStatus != "Stopped") {
            sendPropertiesChanged()
        }

        // 4. Основной цикл обработки входящих вызовов
        while (isRunning && channel.isOpen) {
            val msg = readDbusMessage(channel)
            if (msg == null) {
                log("Получен EOF от сокета D-Bus")
                break
            }
            handleIncomingMessage(channel, msg)
        }
        log("Основной цикл обработки D-Bus завершился")
    }

    private fun handleIncomingMessage(channel: SocketChannel, msg: DbusMessage) {
        if (msg.type != 1) return // Обрабатываем только METHOD_CALL

        val iface = msg.iface ?: ""
        val member = msg.member ?: ""
        val path = msg.path ?: ""
        val sender = msg.sender
        log("Входящий вызов: member='$member', iface='$iface', path='$path', sender='$sender'")

        try {
            // 1. Introspectable
            if (member == "Introspect") {
                val xml = when (path) {
                    "/org/mpris/MediaPlayer2", "/org/mpris/MediaPlayer2/Player" -> getIntrospectionXml()
                    "/" -> """
                        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
                        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
                        <node>
                          <node name="org"/>
                        </node>
                    """.trimIndent()
                    "/org" -> """
                        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
                        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
                        <node>
                          <node name="mpris"/>
                        </node>
                    """.trimIndent()
                    "/org/mpris" -> """
                        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
                        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
                        <node>
                          <node name="MediaPlayer2"/>
                        </node>
                    """.trimIndent()
                    else -> """
                        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
                        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
                        <node></node>
                    """.trimIndent()
                }
                sendMethodReturnString(channel, msg.serial, sender, xml)
                return
            }

            if (path == "/org/mpris/MediaPlayer2" || path == "/org/mpris/MediaPlayer2/Player") {
                when (member) {
                    "Get" -> {
                        val stream = ByteBuffer.wrap(msg.body).order(ByteOrder.LITTLE_ENDIAN)
                        val targetIface = readDbusStringSafe(stream)
                        val propName = readDbusStringSafe(stream)
                        log("Запрос свойства: iface='$targetIface', prop='$propName'")

                        val knownInterfaces = setOf(
                            "org.mpris.MediaPlayer2",
                            "org.mpris.MediaPlayer2.Player",
                            "org.freedesktop.DBus.Properties",
                            "org.freedesktop.DBus.Introspectable",
                            ""
                        )
                        if (targetIface !in knownInterfaces) {
                            sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownInterface", "Unknown interface '$targetIface'")
                            return
                        }
                        if (targetIface == "org.freedesktop.DBus.Properties" || targetIface == "org.freedesktop.DBus.Introspectable") {
                            sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownProperty", "Interface '$targetIface' has no properties")
                            return
                        }

                        val writer = DbusWriter()
                        when (propName) {
                            "PlaybackStatus" -> writer.writeVariant("s") { it.writeString(playbackStatus) }
                            "LoopStatus" -> writer.writeVariant("s") { it.writeString(loopStatus) }
                            "Shuffle" -> writer.writeVariant("b") { it.writeBoolean(shuffle) }
                            "Metadata" -> writer.writeVariant("a{sv}") { writeMetadata(it) }
                            "Volume" -> writer.writeVariant("d") { it.writeDouble(volume.toDouble()) }
                            "Position" -> writer.writeVariant("x") { it.writeInt64(positionMicrosec) }
                            "Rate", "MinimumRate", "MaximumRate" -> writer.writeVariant("d") { it.writeDouble(1.0) }
                            "Identity" -> writer.writeVariant("s") { it.writeString("YamSync") }
                            "DesktopEntry" -> writer.writeVariant("s") { it.writeString(desktopEntryName) }
                            "CanQuit", "CanRaise", "CanControl", "CanPlay", "CanPause", "CanSeek", "CanGoNext", "CanGoPrevious" -> {
                                writer.writeVariant("b") { it.writeBoolean(true) }
                            }
                            "HasTrackList" -> writer.writeVariant("b") { it.writeBoolean(false) }
                            "SupportedUriSchemes" -> writer.writeVariant("as") {
                                it.writeArray(4) { arr -> arr.writeString("yamsync") }
                            }
                            "SupportedMimeTypes" -> writer.writeVariant("as") { it.writeArray(4) {} }
                            else -> {
                                sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownProperty", "Unknown property '$propName'")
                                return
                            }
                        }
                        sendMethodReturnRaw(channel, msg.serial, sender, "v", writer.toByteArray())
                    }
                    "GetAll" -> {
                        val stream = ByteBuffer.wrap(msg.body).order(ByteOrder.LITTLE_ENDIAN)
                        val targetIface = readDbusStringSafe(stream)
                        log("Запрос GetAll для iface='$targetIface'")

                        val knownInterfaces = setOf(
                            "org.mpris.MediaPlayer2",
                            "org.mpris.MediaPlayer2.Player",
                            "org.freedesktop.DBus.Properties",
                            "org.freedesktop.DBus.Introspectable",
                            ""
                        )
                        if (targetIface !in knownInterfaces) {
                            sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownInterface", "Unknown interface '$targetIface'")
                            return
                        }

                        val writer = DbusWriter()
                        writer.writeArray(8) { props ->
                            if (targetIface == "org.mpris.MediaPlayer2" || targetIface.isEmpty()) {
                                props.writeDictEntry("CanQuit", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanRaise", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("HasTrackList", "b") { it.writeBoolean(false) }
                                props.writeDictEntry("Identity", "s") { it.writeString("YamSync") }
                                props.writeDictEntry("DesktopEntry", "s") { it.writeString(desktopEntryName) }
                                props.writeDictEntry("SupportedUriSchemes", "as") {
                                    it.writeArray(4) { arr -> arr.writeString("yamsync") }
                                }
                                props.writeDictEntry("SupportedMimeTypes", "as") { it.writeArray(4) {} }
                            }
                            if (targetIface == "org.mpris.MediaPlayer2.Player" || targetIface.isEmpty()) {
                                props.writeDictEntry("PlaybackStatus", "s") { it.writeString(playbackStatus) }
                                props.writeDictEntry("LoopStatus", "s") { it.writeString(loopStatus) }
                                props.writeDictEntry("Rate", "d") { it.writeDouble(1.0) }
                                props.writeDictEntry("Shuffle", "b") { it.writeBoolean(shuffle) }
                                props.writeDictEntry("Metadata", "a{sv}") { writeMetadata(it) }
                                props.writeDictEntry("Volume", "d") { it.writeDouble(volume.toDouble()) }
                                props.writeDictEntry("Position", "x") { it.writeInt64(positionMicrosec) }
                                props.writeDictEntry("MinimumRate", "d") { it.writeDouble(1.0) }
                                props.writeDictEntry("MaximumRate", "d") { it.writeDouble(1.0) }
                                props.writeDictEntry("CanControl", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanPlay", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanPause", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanSeek", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanGoNext", "b") { it.writeBoolean(true) }
                                props.writeDictEntry("CanGoPrevious", "b") { it.writeBoolean(true) }
                            }
                        }
                        sendMethodReturnRaw(channel, msg.serial, sender, "a{sv}", writer.toByteArray())
                    }
                    "PlayPause" -> {
                        log("Действие: PlayPause")
                        onTogglePlayPause()
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Play" -> {
                        log("Действие: Play")
                        onPlay()
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Pause", "Stop" -> {
                        log("Действие: Pause/Stop")
                        onPause()
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Next" -> {
                        log("Действие: Next")
                        onNext()
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Previous" -> {
                        log("Действие: Previous")
                        onPrev()
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Seek" -> {
                        val stream = ByteBuffer.wrap(msg.body).order(ByteOrder.LITTLE_ENDIAN)
                        if (stream.remaining() >= 8) {
                            align(stream, 8)
                            if (stream.remaining() >= 8) {
                                val offsetUs = stream.long
                                log("Действие: Seek offsetUs=$offsetUs")
                                val newPosUs = (positionMicrosec + offsetUs).coerceAtLeast(0L)
                                onSeek(newPosUs / 1000L)
                            }
                        }
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "SetPosition" -> {
                        val stream = ByteBuffer.wrap(msg.body).order(ByteOrder.LITTLE_ENDIAN)
                        readDbusStringSafe(stream)
                        if (stream.remaining() >= 8) {
                            align(stream, 8)
                            if (stream.remaining() >= 8) {
                                val posUs = stream.long
                                log("Действие: SetPosition posUs=$posUs")
                                onSeek(posUs / 1000L)
                            }
                        }
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    "Set" -> {
                        val stream = ByteBuffer.wrap(msg.body).order(ByteOrder.LITTLE_ENDIAN)
                        val targetIface = readDbusStringSafe(stream)
                        val propName = readDbusStringSafe(stream)
                        log("Действие: Set iface='$targetIface', prop='$propName'")

                        val readOnlyProps = setOf(
                            "PlaybackStatus", "Metadata", "Position", "MinimumRate", "MaximumRate",
                            "CanControl", "CanPlay", "CanPause", "CanSeek", "CanGoNext", "CanGoPrevious",
                            "Identity", "DesktopEntry", "CanQuit", "CanRaise", "HasTrackList",
                            "SupportedUriSchemes", "SupportedMimeTypes"
                        )
                        if (propName in readOnlyProps) {
                            sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.PropertyReadOnly", "Property '$propName' is read-only")
                        } else if (propName == "Volume") {
                            try {
                                if (stream.remaining() >= 2) {
                                    val sigLen = stream.get().toInt() and 0xFF
                                    if (stream.remaining() >= sigLen + 1) {
                                        val sigBytes = ByteArray(sigLen)
                                        stream.get(sigBytes)
                                        stream.get() // null
                                        val sig = String(sigBytes, Charsets.US_ASCII)
                                        if (sig == "d") {
                                            align(stream, 8)
                                            if (stream.remaining() >= 8) {
                                                val newVol = stream.double.toFloat().coerceIn(0f, 1f)
                                                log("Действие: Set Volume=$newVol")
                                                this.volume = newVol
                                                onVolumeChanged?.invoke(newVol)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                log("Ошибка Set Volume: ${e.message}")
                            }
                            sendEmptyMethodReturn(channel, msg.serial, sender)
                        } else if (propName == "LoopStatus") {
                            try {
                                if (stream.remaining() >= 2) {
                                    val sigLen = stream.get().toInt() and 0xFF
                                    if (stream.remaining() >= sigLen + 1) {
                                        val sigBytes = ByteArray(sigLen)
                                        stream.get(sigBytes)
                                        stream.get() // null
                                        val sig = String(sigBytes, Charsets.US_ASCII)
                                        if (sig == "s") {
                                            val newLoop = readDbusStringSafe(stream)
                                            log("Действие: Set LoopStatus=$newLoop")
                                            this.loopStatus = newLoop
                                            sendPropertiesChanged()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                log("Ошибка Set LoopStatus: ${e.message}")
                            }
                            sendEmptyMethodReturn(channel, msg.serial, sender)
                        } else if (propName == "Shuffle") {
                            try {
                                if (stream.remaining() >= 2) {
                                    val sigLen = stream.get().toInt() and 0xFF
                                    if (stream.remaining() >= sigLen + 1) {
                                        val sigBytes = ByteArray(sigLen)
                                        stream.get(sigBytes)
                                        stream.get() // null
                                        val sig = String(sigBytes, Charsets.US_ASCII)
                                        if (sig == "b") {
                                            align(stream, 4)
                                            if (stream.remaining() >= 4) {
                                                val newShuffle = stream.int != 0
                                                log("Действие: Set Shuffle=$newShuffle")
                                                this.shuffle = newShuffle
                                                sendPropertiesChanged()
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                log("Ошибка Set Shuffle: ${e.message}")
                            }
                            sendEmptyMethodReturn(channel, msg.serial, sender)
                        } else if (propName == "Rate") {
                            sendEmptyMethodReturn(channel, msg.serial, sender)
                        } else {
                            sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownProperty", "Unknown property '$propName'")
                        }
                    }
                    "Raise", "Quit" -> {
                        sendEmptyMethodReturn(channel, msg.serial, sender)
                    }
                    else -> {
                        sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownMethod", "Method '$member' on interface '$iface' doesn't exist")
                    }
                }
            } else {
                sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.UnknownObject", "No such object path '$path'")
            }
        } catch (e: Throwable) {
            log("Исключение при обработке вызова member='$member': ${e.message}", e)
            if (channel.isOpen && e !is java.io.IOException) {
                try {
                    sendError(channel, msg.serial, sender, "org.freedesktop.DBus.Error.Failed", e.message ?: "Internal error")
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendPropertiesChanged() {
        val channel = socketChannel
        if (channel == null || !channel.isOpen) {
            log("sendPropertiesChanged: сокет не активен, пропуск")
            return
        }

        try {
            log("Отправка PropertiesChanged: status=$playbackStatus, track='$trackTitle', artist='$artistName'")
            val serial = serialCounter.getAndIncrement()
            val writer = DbusWriter()
            writer.writeString("org.mpris.MediaPlayer2.Player")
            writer.writeArray(8) { props ->
                props.writeDictEntry("PlaybackStatus", "s") { it.writeString(playbackStatus) }
                props.writeDictEntry("LoopStatus", "s") { it.writeString(loopStatus) }
                props.writeDictEntry("Shuffle", "b") { it.writeBoolean(shuffle) }
                props.writeDictEntry("Metadata", "a{sv}") { writeMetadata(it) }
                props.writeDictEntry("Volume", "d") { it.writeDouble(volume.toDouble()) }
            }
            // Пустой массив invalidated properties (as)
            writer.writeArray(4) {}

            sendSignal(
                channel = channel,
                serial = serial,
                path = "/org/mpris/MediaPlayer2",
                iface = "org.freedesktop.DBus.Properties",
                member = "PropertiesChanged",
                signature = "sa{sv}as",
                body = writer.toByteArray()
            )
        } catch (e: Exception) {
            log("Ошибка отправки PropertiesChanged: ${e.message}", e)
            cleanupSocket()
        }
    }

    private fun writeMetadata(writer: DbusWriter) {
        writer.writeArray(8) { arr ->
            val cleanId = trackId.replace(Regex("[^a-zA-Z0-9_]"), "_").ifBlank { "0" }
            arr.writeDictEntry("mpris:trackid", "o") { it.writeObjectPath("/org/mpris/MediaPlayer2/track/$cleanId") }
            arr.writeDictEntry("mpris:length", "x") { it.writeInt64(durationMicrosec) }
            arr.writeDictEntry("xesam:title", "s") { it.writeString(trackTitle) }
            arr.writeDictEntry("xesam:artist", "as") { artistArr ->
                artistArr.writeArray(4) { sArr ->
                    if (artistName.isNotBlank()) {
                        sArr.writeString(artistName)
                    }
                }
            }
            if (artistName.isNotBlank()) {
                arr.writeDictEntry("xesam:albumArtist", "as") { artistArr ->
                    artistArr.writeArray(4) { sArr ->
                        sArr.writeString(artistName)
                    }
                }
            }
            if (albumName.isNotBlank()) {
                arr.writeDictEntry("xesam:album", "s") { it.writeString(albumName) }
            }
            if (artUrl.isNotBlank()) {
                arr.writeDictEntry("mpris:artUrl", "s") { it.writeString(artUrl) }
            }
        }
    }

    private fun getIntrospectionXml(): String {
        return """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
            "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node>
              <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect">
                  <arg name="data" direction="out" type="s"/>
                </method>
              </interface>
              <interface name="org.freedesktop.DBus.Properties">
                <method name="Get">
                  <arg name="interface_name" direction="in" type="s"/>
                  <arg name="property_name" direction="in" type="s"/>
                  <arg name="value" direction="out" type="v"/>
                </method>
                <method name="GetAll">
                  <arg name="interface_name" direction="in" type="s"/>
                  <arg name="properties" direction="out" type="a{sv}"/>
                </method>
                <method name="Set">
                  <arg name="interface_name" direction="in" type="s"/>
                  <arg name="property_name" direction="in" type="s"/>
                  <arg name="value" direction="in" type="v"/>
                </method>
                <signal name="PropertiesChanged">
                  <arg name="interface_name" type="s"/>
                  <arg name="changed_properties" type="a{sv}"/>
                  <arg name="invalidated_properties" type="as"/>
                </signal>
              </interface>
              <interface name="org.mpris.MediaPlayer2">
                <method name="Raise"/>
                <method name="Quit"/>
                <property name="CanQuit" type="b" access="read"/>
                <property name="CanRaise" type="b" access="read"/>
                <property name="HasTrackList" type="b" access="read"/>
                <property name="Identity" type="s" access="read"/>
                <property name="DesktopEntry" type="s" access="read"/>
                <property name="SupportedUriSchemes" type="as" access="read"/>
                <property name="SupportedMimeTypes" type="as" access="read"/>
              </interface>
              <interface name="org.mpris.MediaPlayer2.Player">
                <method name="Next"/>
                <method name="Previous"/>
                <method name="Pause"/>
                <method name="PlayPause"/>
                <method name="Stop"/>
                <method name="Play"/>
                <method name="Seek">
                  <arg name="Offset" direction="in" type="x"/>
                </method>
                <method name="SetPosition">
                  <arg name="TrackId" direction="in" type="o"/>
                  <arg name="Position" direction="in" type="x"/>
                </method>
                <property name="PlaybackStatus" type="s" access="read"/>
                <property name="LoopStatus" type="s" access="readwrite"/>
                <property name="Rate" type="d" access="readwrite"/>
                <property name="Shuffle" type="b" access="readwrite"/>
                <property name="Metadata" type="a{sv}" access="read"/>
                <property name="Volume" type="d" access="readwrite"/>
                <property name="Position" type="x" access="read"/>
                <property name="MinimumRate" type="d" access="read"/>
                <property name="MaximumRate" type="d" access="read"/>
                <property name="CanControl" type="b" access="read"/>
                <property name="CanPlay" type="b" access="read"/>
                <property name="CanPause" type="b" access="read"/>
                <property name="CanSeek" type="b" access="read"/>
                <property name="CanGoNext" type="b" access="read"/>
                <property name="CanGoPrevious" type="b" access="read"/>
              </interface>
            </node>
        """.trimIndent()
    }

    // ==========================================================
    // 📦 D-BUS БИНАРНЫЙ ПРОТОКОЛ (НИЗКОУРОВНЕВАЯ СЕРИАЛИЗАЦИЯ)
    // ==========================================================

    private fun sendMethodReturnString(channel: SocketChannel, replySerial: Int, destination: String?, value: String) {
        val body = DbusWriter().apply { writeString(value) }.toByteArray()
        sendMethodReturnRaw(channel, replySerial, destination, "s", body)
    }

    private fun sendEmptyMethodReturn(channel: SocketChannel, replySerial: Int, destination: String?) {
        sendMethodReturnRaw(channel, replySerial, destination, "", byteArrayOf())
    }

    private fun sendMethodReturnRaw(channel: SocketChannel, replySerial: Int, destination: String?, signature: String, body: ByteArray) {
        val serial = serialCounter.getAndIncrement()
        val fields = ByteArrayOutputStream().apply {
            writeHeaderField(5, "u") { it.writeDbusUInt32(replySerial) } // REPLY_SERIAL
            if (destination != null) {
                writeHeaderField(6, "s") { it.writeDbusString(destination) } // DESTINATION
            }
            if (signature.isNotEmpty()) {
                writeHeaderField(8, "g") { it.writeDbusSignature(signature) } // SIGNATURE
            }
        }.toByteArray()

        sendPacket(channel, 2, 0, body.size, serial, fields, body)
    }

    private fun sendError(channel: SocketChannel, replySerial: Int, destination: String?, errorName: String, errorMsg: String) {
        val serial = serialCounter.getAndIncrement()
        val body = DbusWriter().apply { writeString(errorMsg) }.toByteArray()
        val fields = ByteArrayOutputStream().apply {
            writeHeaderField(4, "s") { it.writeDbusString(errorName) } // ERROR_NAME
            writeHeaderField(5, "u") { it.writeDbusUInt32(replySerial) } // REPLY_SERIAL
            if (destination != null) {
                writeHeaderField(6, "s") { it.writeDbusString(destination) }
            }
            writeHeaderField(8, "g") { it.writeDbusSignature("s") }
        }.toByteArray()

        sendPacket(channel, 3, 0, body.size, serial, fields, body)
    }

    private fun sendSignal(channel: SocketChannel, serial: Int, path: String, iface: String, member: String, signature: String, body: ByteArray) {
        val fields = ByteArrayOutputStream().apply {
            writeHeaderField(1, "o") { it.writeDbusObjectPath(path) }
            writeHeaderField(2, "s") { it.writeDbusString(iface) }
            writeHeaderField(3, "s") { it.writeDbusString(member) }
            if (signature.isNotEmpty()) {
                writeHeaderField(8, "g") { it.writeDbusSignature(signature) }
            }
        }.toByteArray()

        sendPacket(channel, 4, 0, body.size, serial, fields, body)
    }

    private fun sendMethodCall(channel: SocketChannel, serial: Int, destination: String, path: String, iface: String, member: String, signature: String, body: ByteArray) {
        val fields = ByteArrayOutputStream().apply {
            writeHeaderField(1, "o") { it.writeDbusObjectPath(path) }
            writeHeaderField(2, "s") { it.writeDbusString(iface) }
            writeHeaderField(3, "s") { it.writeDbusString(member) }
            writeHeaderField(6, "s") { it.writeDbusString(destination) }
            if (signature.isNotEmpty()) {
                writeHeaderField(8, "g") { it.writeDbusSignature(signature) }
            }
        }.toByteArray()

        sendPacket(channel, 1, 0, body.size, serial, fields, body)
    }

    private fun sendPacket(channel: SocketChannel, type: Int, flags: Int, bodyLength: Int, serial: Int, fields: ByteArray, body: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write('l'.code) // Little-endian
        header.write(type)
        header.write(flags)
        header.write(1) // Major version
        header.writeDbusUInt32(bodyLength)
        header.writeDbusUInt32(serial)
        header.writeDbusUInt32(fields.size)
        header.write(fields)

        // Выравнивание заголовка по 8 байт
        val pad = (8 - (header.size() % 8)) % 8
        repeat(pad) { header.write(0) }

        val totalBytes = ByteBuffer.allocate(header.size() + body.size)
        totalBytes.put(header.toByteArray())
        totalBytes.put(body)
        totalBytes.flip()

        synchronized(channel) {
            if (channel.isOpen) {
                while (totalBytes.hasRemaining()) {
                    val written = channel.write(totalBytes)
                    if (written < 0) throw java.io.IOException("Socket channel closed during write")
                }
            }
        }
    }

    private class DbusMessage(
        val type: Int,
        val serial: Int,
        val replySerial: Int?,
        val path: String?,
        val iface: String?,
        val member: String?,
        val sender: String?,
        val errorName: String?,
        val body: ByteArray
    ) {
        fun readStringBody(): String {
            return try {
                if (body.size >= 4) {
                    val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val len = buf.int
                    if (len in 0..buf.remaining()) {
                        val bytes = ByteArray(len)
                        buf.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else ""
                } else ""
            } catch (_: Exception) { "" }
        }
    }

    private fun readDbusMessage(channel: SocketChannel): DbusMessage? {
        val fixedHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        while (fixedHeader.hasRemaining()) {
            val read = channel.read(fixedHeader)
            if (read == -1) return null
        }
        fixedHeader.flip()

        fixedHeader.get() // endian ('l' или 'B')
        val type = fixedHeader.get().toInt()
        fixedHeader.get() // flags
        fixedHeader.get() // version
        val bodyLen = fixedHeader.int
        val serial = fixedHeader.int
        val fieldsLen = fixedHeader.int

        if (fieldsLen < 0 || fieldsLen > 1024 * 1024) return null
        if (bodyLen < 0 || bodyLen > 16 * 1024 * 1024) return null

        val pad = (8 - ((16 + fieldsLen) % 8)) % 8
        val restHeaderLen = fieldsLen + pad
        val restHeader = ByteBuffer.allocate(restHeaderLen).order(ByteOrder.LITTLE_ENDIAN)
        while (restHeader.hasRemaining()) {
            val read = channel.read(restHeader)
            if (read == -1) return null
        }
        restHeader.flip()

        var path: String? = null
        var iface: String? = null
        var member: String? = null
        var sender: String? = null
        var errorName: String? = null
        var replySerial: Int? = null

        val fieldsLimit = restHeader.position() + fieldsLen
        while (restHeader.position() < fieldsLimit) {
            align(restHeader, 8)
            if (restHeader.position() >= fieldsLimit) break
            if (restHeader.remaining() < 2) break
            val fieldCode = restHeader.get().toInt()
            val sigLen = restHeader.get().toInt() and 0xFF
            if (restHeader.remaining() < sigLen + 1) break
            val sigBytes = ByteArray(sigLen)
            restHeader.get(sigBytes)
            restHeader.get() // null

            when (fieldCode) {
                1 -> path = readDbusObjectPath(restHeader)
                2 -> iface = readDbusStringSafe(restHeader)
                3 -> member = readDbusStringSafe(restHeader)
                4 -> errorName = readDbusStringSafe(restHeader)
                5 -> {
                    align(restHeader, 4)
                    if (restHeader.remaining() >= 4) {
                        replySerial = restHeader.int
                    }
                }
                7 -> sender = readDbusStringSafe(restHeader)
                else -> skipDbusVariant(restHeader, String(sigBytes))
            }
        }

        val body = ByteArray(bodyLen)
        if (bodyLen > 0) {
            val bodyBuf = ByteBuffer.wrap(body)
            while (bodyBuf.hasRemaining()) {
                val read = channel.read(bodyBuf)
                if (read == -1) return null
            }
        }

        return DbusMessage(type, serial, replySerial, path, iface, member, sender, errorName, body)
    }

    private fun align(buf: ByteBuffer, alignment: Int) {
        val pos = buf.position()
        val rem = pos % alignment
        if (rem != 0) {
            val nextPos = pos + (alignment - rem)
            if (nextPos <= buf.limit()) {
                buf.position(nextPos)
            }
        }
    }

    private fun readDbusStringSafe(buf: ByteBuffer): String {
        align(buf, 4)
        if (buf.remaining() < 4) return ""
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        if (buf.hasRemaining()) {
            buf.get() // null terminator
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readDbusObjectPath(buf: ByteBuffer): String = readDbusStringSafe(buf)

    private fun skipDbusVariant(buf: ByteBuffer, sig: String) {
        try {
            if (sig.startsWith("a")) {
                align(buf, 4)
                if (buf.remaining() >= 4) {
                    val arrLen = buf.int
                    if (arrLen > 0 && buf.remaining() >= arrLen) {
                        buf.position(buf.position() + arrLen)
                    }
                }
                return
            }
            when (sig) {
                "s", "o" -> readDbusStringSafe(buf)
                "g" -> {
                    if (buf.hasRemaining()) {
                        val sigLen = buf.get().toInt() and 0xFF
                        if (buf.remaining() >= sigLen + 1) {
                            buf.position(buf.position() + sigLen + 1)
                        }
                    }
                }
                "u", "i" -> { align(buf, 4); if (buf.remaining() >= 4) buf.int }
                "x", "t", "d" -> { align(buf, 8); if (buf.remaining() >= 8) buf.long }
                "y", "b" -> { if (buf.hasRemaining()) buf.get() }
                "v" -> {
                    if (buf.hasRemaining()) {
                        val nestedSigLen = buf.get().toInt() and 0xFF
                        if (buf.remaining() >= nestedSigLen + 1) {
                            val nestedSigBytes = ByteArray(nestedSigLen)
                            buf.get(nestedSigBytes)
                            buf.get() // null
                            skipDbusVariant(buf, String(nestedSigBytes, Charsets.US_ASCII))
                        }
                    }
                }
                else -> {}
            }
        } catch (_: Exception) {}
    }

    private fun ByteArrayOutputStream.writeHeaderField(code: Int, sig: String, valueWriter: (ByteArrayOutputStream) -> Unit) {
        alignOutput(8)
        write(code)
        writeDbusSignature(sig)
        valueWriter(this)
    }

    private fun ByteArrayOutputStream.writeDbusString(str: String) {
        alignOutput(4)
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeDbusUInt32(bytes.size)
        write(bytes)
        write(0) // null
    }

    private fun ByteArrayOutputStream.writeDbusObjectPath(path: String) = writeDbusString(path)

    private fun ByteArrayOutputStream.writeDbusSignature(sig: String) {
        val bytes = sig.toByteArray(Charsets.US_ASCII)
        write(bytes.size)
        write(bytes)
        write(0) // null
    }

    private fun ByteArrayOutputStream.writeDbusUInt32(value: Int) {
        alignOutput(4)
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.alignOutput(alignment: Int) {
        val rem = size() % alignment
        if (rem != 0) {
            repeat(alignment - rem) { write(0) }
        }
    }

    private class PatchedByteArrayOutputStream : ByteArrayOutputStream() {
        fun setInt32LE(offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }

    private class DbusWriter(val stream: PatchedByteArrayOutputStream = PatchedByteArrayOutputStream()) {
        val size: Int get() = stream.size()

        fun align(alignment: Int) {
            val rem = stream.size() % alignment
            if (rem != 0) {
                repeat(alignment - rem) { stream.write(0) }
            }
        }

        fun writeByte(value: Int) {
            stream.write(value)
        }

        fun writeBoolean(value: Boolean) {
            writeUInt32(if (value) 1 else 0)
        }

        fun writeUInt32(value: Int) {
            align(4)
            stream.write(value and 0xFF)
            stream.write((value ushr 8) and 0xFF)
            stream.write((value ushr 16) and 0xFF)
            stream.write((value ushr 24) and 0xFF)
        }

        fun writeInt64(value: Long) {
            align(8)
            for (i in 0..7) {
                stream.write(((value ushr (i * 8)) and 0xFF).toInt())
            }
        }

        fun writeDouble(value: Double) {
            writeInt64(java.lang.Double.doubleToRawLongBits(value))
        }

        fun writeString(str: String) {
            align(4)
            val bytes = str.toByteArray(Charsets.UTF_8)
            writeUInt32(bytes.size)
            stream.write(bytes)
            stream.write(0) // null terminator
        }

        fun writeObjectPath(path: String) = writeString(path)

        fun writeSignature(sig: String) {
            val bytes = sig.toByteArray(Charsets.US_ASCII)
            stream.write(bytes.size)
            stream.write(bytes)
            stream.write(0) // null terminator
        }

        fun writeArray(elementAlignment: Int, block: (DbusWriter) -> Unit) {
            align(4)
            val lengthPos = stream.size()
            writeUInt32(0) // 4 bytes placeholder for array byte length

            align(elementAlignment)
            val dataStart = stream.size()
            block(this)
            val dataEnd = stream.size()

            val dataLen = dataEnd - dataStart
            stream.setInt32LE(lengthPos, dataLen)
        }

        fun writeDictEntry(key: String, valSig: String, valueWriter: (DbusWriter) -> Unit) {
            align(8)
            writeString(key)
            writeSignature(valSig)
            valueWriter(this)
        }

        fun writeVariant(sig: String, block: (DbusWriter) -> Unit) {
            writeSignature(sig)
            block(this)
        }

        fun toByteArray(): ByteArray = stream.toByteArray()
    }

    private fun readAsciiLine(channel: SocketChannel): String {
        val sb = StringBuilder()
        val buf = ByteBuffer.allocate(1)
        while (channel.read(buf) > 0) {
            buf.flip()
            val c = buf.get().toInt().toChar()
            buf.clear()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }

    private fun resolveDBusAddress(): Path? {
        val env = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        if (!env.isNullOrBlank()) {
            val prefix = "unix:path="
            val idx = env.indexOf(prefix)
            if (idx != -1) {
                val pathStr = env.substring(idx + prefix.length).substringBefore(",").substringBefore(";")
                val p = Path.of(pathStr)
                if (Files.exists(p)) return p
            }
        }

        // Fallback: /run/user/<uid>/bus
        val uid = getUnixUid()
        val fallback = Path.of("/run/user/$uid/bus")
        if (Files.exists(fallback)) return fallback

        // Fallback: если путь передан без префикса
        if (!env.isNullOrBlank() && env.startsWith("/")) {
            val p = Path.of(env.substringBefore(",").substringBefore(";"))
            if (Files.exists(p)) return p
        }

        return null
    }

    private fun getUnixUid(): String {
        try {
            val p = ProcessBuilder("id", "-u").start()
            val output = p.inputStream.bufferedReader().readText().trim()
            if (output.isNotEmpty()) return output
        } catch (_: Exception) {}
        return "1000"
    }
}
