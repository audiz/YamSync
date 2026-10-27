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
    /** Пик нормированной автокорреляции ряда (60 fps) на лагах 0.33..2 с после удаления медленного дрейфа. */
    private fun rhythmPeak(series: List<Float>): Pair<Double, Int> {
        val x = DoubleArray(series.size)
        var ema = series.firstOrNull()?.toDouble() ?: 0.0
        for (t in series.indices) { ema += (series[t] - ema) / 30.0; x[t] = series[t] - ema }
        var r0 = 0.0; for (v in x) r0 += v * v
        if (r0 <= 0.0) return 0.0 to 1
        val acf = DoubleArray(122)
        for (lag in 19..121) {
            var r = 0.0
            for (t in 0 until x.size - lag) r += x[t] * x[t + lag]
            acf[lag] = r / r0
        }
        var bestR = 0.0; var bestLag = 1
        for (lag in 20..120) {
            if (acf[lag] > acf[lag - 1] && acf[lag] >= acf[lag + 1] && acf[lag] > bestR) { bestR = acf[lag]; bestLag = lag }
        }
        return bestR to bestLag
    }

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
            var bpmSum = 0.0; var confSum = 0.0
            val bpmTrace = StringBuilder()
            var speedSum = 0.0; var levelSum = 0.0; var paceSum = 0.0; var frames = 0
            val meanSeries = ArrayList<Float>()
            val barSeries = Array(25) { ArrayList<Float>() }
            val barSum = DoubleArray(25); val barSqSum = DoubleArray(25)
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
                        bpmSum += viz.detectedBpm; confSum += viz.beatConfidence
                        if (frames % 300 == 0) bpmTrace.append(" %.0f/%.1f".format(viz.detectedBpm, viz.beatConfidence))
                        var m = 0f
                        for (i in 0 until 25) { m += l[i]; barSum[i] += l[i]; barSqSum[i] += l[i] * l[i]; barSeries[i].add(l[i]) }
                        meanSeries.add(m / 25f)
                    }
                    prev = l
                    nextFrameMs += frameDt
                }
                simMs = chunkEnd
            }
            val secs = frames / 60.0
            input.close()
            // Когерентность: дисперсия среднего уровня относительно средней дисперсии отдельных столбиков
            val mAvg = meanSeries.average()
            val mVar = meanSeries.sumOf { (it - mAvg) * (it - mAvg) } / meanSeries.size
            var barVar = 0.0
            for (i in 0 until 25) { val a = barSum[i] / frames; barVar += barSqSum[i] / frames - a * a }
            barVar /= 25
            // Ритмичность: пик нормированной автокорреляции среднего уровня на лагах 0.33..2 с
            val (bestR, bestLag) = rhythmPeak(meanSeries)
            // Ритмичность отдельных столбиков: средний пик автокорреляции по каждому столбику
            val barRhythm = barSeries.map { rhythmPeak(it).first }.average()
            println("PROBE %-8s level=%.2f speed=%.2f/s peaks/bar/s=%.2f pace=%.2f coherence=%.2f rhythm=%.2f@%.0fbpm barRhythm=%.2f tracker=%.0fbpm/%.2f".format(
                f.nameWithoutExtension, levelSum / frames / 25, speedSum / frames / 25, peaks / secs / 25, paceSum / frames,
                mVar / barVar, bestR, 3600.0 / bestLag, barRhythm, bpmSum / frames, confSum / frames))
        }
    }
}
