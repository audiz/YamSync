package io.github.audiz

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 🪟 Windows System Media Transport Controls (SMTC) Bridge.
 * Обеспечивает интеграцию со системным оверлеем громкости Windows 10/11,
 * мультимедийными клавишами клавиатуры и Bluetooth-гарнитур.
 */
class WindowsMediaControls(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onTogglePlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onSeek: (Long) -> Unit
) {
    @Volatile
    private var isRunning = false
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    fun start() {
        if (isRunning) return
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
        if (!isWindows) return

        Thread {
            try {
                val exeFile = ensureExecutableReady() ?: run {
                    println("WindowsMediaControls: Не удалось подготовить bridge executable")
                    return@Thread
                }

                val pb = ProcessBuilder(exeFile.absolutePath)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                process = p
                isRunning = true

                val w = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
                writer = w

                val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
                println("🪟 WindowsMediaControls: SMTC мост успешно запущен!")

                while (isRunning) {
                    val rawLine = reader.readLine() ?: break
                    val cmd = rawLine.trim()
                    if (cmd.isEmpty()) continue
                    when (cmd) {
                        "PLAY" -> onPlay()
                        "PAUSE" -> onPause()
                        "TOGGLE" -> onTogglePlayPause()
                        "NEXT" -> onNext()
                        "PREV" -> onPrev()
                    }
                }
            } catch (e: Exception) {
                println("WindowsMediaControls: Ошибка процесса моста: ${e.message}")
            } finally {
                isRunning = false
                writer = null
                process = null
            }
        }.apply {
            isDaemon = true
            name = "Windows-SMTC-Thread"
            start()
        }
    }

    fun stop() {
        isRunning = false
        sendLine("QUIT")
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null

        val p = process
        process = null
        if (p != null && p.isAlive) {
            try {
                p.destroy()
            } catch (_: Exception) {}
        }
    }

    fun updateMetadata(title: String, artist: String, album: String) {
        val cleanTitle = title.ifBlank { "Yandex Music" }.replace("\t", " ").replace("\n", " ")
        val cleanArtist = artist.replace("\t", " ").replace("\n", " ")
        val cleanAlbum = album.replace("\t", " ").replace("\n", " ")
        sendLine("META $cleanTitle\t$cleanArtist\t$cleanAlbum")
    }

    fun updatePlaybackState(isPlaying: Boolean, isPaused: Boolean) {
        val state = when {
            isPlaying && !isPaused -> "Playing"
            isPaused -> "Paused"
            else -> "Stopped"
        }
        sendLine("STATE $state")
    }

    fun clear() {
        sendLine("CLEAR")
    }

    private fun sendLine(line: String) {
        if (!isRunning) return
        try {
            synchronized(writeLock) {
                writer?.let {
                    it.write(line)
                    it.newLine()
                    it.flush()
                }
            }
        } catch (_: Exception) {}
    }

    private fun ensureExecutableReady(): File? {
        val targetExe = File(System.getProperty("java.io.tmpdir"), "yamusic_smtc.exe")
        if (targetExe.exists() && targetExe.length() > 0) {
            return targetExe
        }

        val cscPath = listOf(
            "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe",
            "C:\\Windows\\Microsoft.NET\\Framework\\v4.0.30319\\csc.exe"
        ).firstOrNull { File(it).exists() } ?: return null

        val winmdMedia = "C:\\Windows\\System32\\WinMetadata\\Windows.Media.winmd"
        val winmdFoundation = "C:\\Windows\\System32\\WinMetadata\\Windows.Foundation.winmd"
        if (!File(winmdMedia).exists() || !File(winmdFoundation).exists()) {
            return null
        }

        val tempCs = File.createTempFile("yamusic_smtc_", ".cs")
        try {
            tempCs.writeText(CS_SOURCE_CODE, StandardCharsets.UTF_8)

            val dotnetDir = File(cscPath).parentFile.absolutePath
            val compilePb = ProcessBuilder(
                cscPath,
                "/noconfig",
                "/nostdlib",
                "/r:$dotnetDir\\mscorlib.dll",
                "/r:$winmdFoundation",
                "/r:$winmdMedia",
                "/r:$dotnetDir\\System.dll",
                "/r:$dotnetDir\\System.Runtime.dll",
                "/r:$dotnetDir\\System.Runtime.InteropServices.WindowsRuntime.dll",
                "/out:${targetExe.absolutePath}",
                tempCs.absolutePath
            )
            compilePb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val compileProcess = compilePb.start()
            val exitCode = compileProcess.waitFor()
            if (exitCode == 0 && targetExe.exists()) {
                return targetExe
            }
        } catch (e: Exception) {
            println("WindowsMediaControls: Не удалось скомпилировать SMTC мост: ${e.message}")
        } finally {
            try { tempCs.delete() } catch (_: Exception) {}
        }
        return if (targetExe.exists()) targetExe else null
    }

    companion object {
        private const val CS_SOURCE_CODE = """using System;
using System.IO;
using System.Text;
using Windows.Media;
using Windows.Media.Playback;

namespace YaMusicSmtc
{
    class Program
    {
        static MediaPlayer player;
        static SystemMediaTransportControls smtc;
        static SystemMediaTransportControlsDisplayUpdater updater;

        static void Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            Console.InputEncoding = Encoding.UTF8;

            try
            {
                player = new MediaPlayer();
                player.CommandManager.IsEnabled = false;
                smtc = player.SystemMediaTransportControls;
                smtc.IsEnabled = true;
                smtc.IsPlayEnabled = true;
                smtc.IsPauseEnabled = true;
                smtc.IsNextEnabled = true;
                smtc.IsPreviousEnabled = true;

                updater = smtc.DisplayUpdater;
                updater.Type = MediaPlaybackType.Music;
                updater.MusicProperties.Title = "Yandex Music";
                updater.MusicProperties.Artist = "";
                updater.Update();

                smtc.ButtonPressed += (s, e) =>
                {
                    switch (e.Button)
                    {
                        case SystemMediaTransportControlsButton.Play:
                            Console.WriteLine("PLAY");
                            break;
                        case SystemMediaTransportControlsButton.Pause:
                            Console.WriteLine("PAUSE");
                            break;
                        case SystemMediaTransportControlsButton.Next:
                            Console.WriteLine("NEXT");
                            break;
                        case SystemMediaTransportControlsButton.Previous:
                            Console.WriteLine("PREV");
                            break;
                        case SystemMediaTransportControlsButton.Stop:
                            Console.WriteLine("PAUSE");
                            break;
                    }
                };

                Console.WriteLine("READY");

                string line;
                while ((line = Console.ReadLine()) != null)
                {
                    line = line.Trim();
                    if (string.IsNullOrEmpty(line)) continue;
                    if (line == "QUIT" || line == "EXIT") break;

                    if (line.StartsWith("STATE "))
                    {
                        string state = line.Substring(6).Trim();
                        if (state == "Playing")
                            smtc.PlaybackStatus = MediaPlaybackStatus.Playing;
                        else if (state == "Paused")
                            smtc.PlaybackStatus = MediaPlaybackStatus.Paused;
                        else
                            smtc.PlaybackStatus = MediaPlaybackStatus.Stopped;
                    }
                    else if (line.StartsWith("META "))
                    {
                        string[] parts = line.Substring(5).Split('\t');
                        string title = parts.Length > 0 ? parts[0] : "";
                        string artist = parts.Length > 1 ? parts[1] : "";
                        string album = parts.Length > 2 ? parts[2] : "";

                        updater.MusicProperties.Title = title;
                        updater.MusicProperties.Artist = artist;
                        updater.MusicProperties.AlbumTitle = album;
                        updater.Update();
                    }
                    else if (line == "CLEAR")
                    {
                        smtc.PlaybackStatus = MediaPlaybackStatus.Stopped;
                        updater.ClearAll();
                        updater.Update();
                    }
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SMTC Bridge Error: " + ex);
            }
            finally
            {
                try
                {
                    if (smtc != null)
                    {
                        smtc.PlaybackStatus = MediaPlaybackStatus.Stopped;
                        smtc.IsEnabled = false;
                    }
                }
                catch { }
            }
        }
    }
}"""
    }
}
