package io.github.audiz

import io.github.audiz.dsp.PcmSpectrumAnalyzer
import io.github.audiz.dsp.TwentyFiveBandVisualizer
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Ручной замер поведения 25-полосного спектра на реальных треках.
 *
 * Ожидает каталог `VIS_PCM_DIR` с файлами `*.raw` (s16le, 44.1 кГц, стерео), например:
 * `ffmpeg -ss 60 -t 40 -i track.mp3 -f s16le -ac 2 -ar 44100 track.raw`.
 * Без переменной окружения тест ничего не делает.
 */
class VisualizerProbeTest {
    @Test
    fun probe() {
        val dir = System.getenv("VIS_PCM_DIR") ?: return
        for (f in File(dir).listFiles()!!.filter { it.name.endsWith(".raw") }.sortedBy { it.name }) {
            var simMs = 0.0
            val viz = TwentyFiveBandVisualizer(timeProvider = { simMs.toLong() })
            val analyzer = PcmSpectrumAnalyzer()
            analyzer.onSpectrumReady = { viz.updateSpectrum(it) }
            val chunk = 4096
            val buffer = ByteArray(chunk)
            val input = f.inputStream().buffered(256 * 1024)
            val chunkMs = chunk / 4 * 1000.0 / 44100.0
            var nextFrameMs = 0.0
            val frameDt = 1000.0 / 60.0
            var prev: FloatArray? = null
            var speedSum = 0.0; var levelSum = 0.0; var paceSum = 0.0; var frames = 0
            val rising = BooleanArray(25); val lastExtreme = FloatArray(25) { 0.15f }; var peaks = 0
            while (input.readNBytes(buffer, 0, chunk) == chunk) {
                analyzer.processPcmChunk(buffer, chunk)
                val chunkEnd = simMs + chunkMs
                while (nextFrameMs <= chunkEnd) {
                    simMs = nextFrameMs
                    val fr = viz.advanceFrame((frameDt / 1000).toFloat(), true)
                    val l = fr.levels
                    if (simMs > 3000) {
                        prev?.let { p -> for (i in 0 until 25) speedSum += abs(l[i] - p[i]) / (frameDt / 1000) }
                        for (i in 0 until 25) {
                            levelSum += l[i]
                            // пики с амплитудой > 0.15
                            if (rising[i] && l[i] < lastExtreme[i] - 0.15f) { peaks++; rising[i] = false; lastExtreme[i] = l[i] }
                            else if (!rising[i] && l[i] > lastExtreme[i] + 0.15f) { rising[i] = true; lastExtreme[i] = l[i] }
                            else if (rising[i] && l[i] > lastExtreme[i]) lastExtreme[i] = l[i]
                            else if (!rising[i] && l[i] < lastExtreme[i]) lastExtreme[i] = l[i]
                        }
                        paceSum += viz.paceFactor; frames++
                    }
                    prev = l
                    nextFrameMs += frameDt
                }
                simMs = chunkEnd
            }
            input.close()
            val secs = frames / 60.0
            println("PROBE %-6s level=%.2f speed=%.2f/s peaks/bar/s=%.2f pace=%.2f".format(
                f.nameWithoutExtension, levelSum / frames / 25, speedSum / frames / 25, peaks / secs / 25, paceSum / frames))
        }
    }
}
