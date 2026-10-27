package io.github.audiz.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 🥁 Трекер темпа по 25-полосному спектру.
 *
 * - Функция начала нот (onset strength): взвешенный положительный спектральный поток, бас весомее.
 * - Темп: автокорреляция onset strength за последние [HISTORY_SEC] секунд в диапазоне [MIN_BPM]..[MAX_BPM]
 *   с коррекцией половинного/двойного темпа и инерцией против случайных переключений.
 * - Фаза: гребёнка с периодом доли по той же истории даёт момент последней доли,
 *   из которого строится ритмическая сетка (доли и половины долей) — [gridWeight].
 *
 * Не потокобезопасен: вызывается под блокировкой [TwentyFiveBandVisualizer].
 */
internal class BeatTracker {
    companion object {
        private const val HOP_SEC = 0.02f
        private const val HISTORY_SEC = 8f
        private const val HISTORY_SIZE = (HISTORY_SEC / HOP_SEC).toInt()
        private const val MIN_BPM = 60f
        private const val MAX_BPM = 180f
        private const val PREFERRED_BPM = 110f
        // Половинный темп (бочка на 1 и 3) заменяем на полный, если тот не быстрее этого порога
        private const val MAX_PROMOTED_BPM = 140f
        private const val HALF_LAG_MIN_RATIO = 0.5f
        // Инерция темпа: текущий период остаётся, пока его оценка не хуже этой доли от лучшей
        private const val STICKY_SCORE_RATIO = 0.7f
        // Сколько оценок подряд новый темп должен побеждать, чтобы на него переключиться
        private const val SWITCH_PERSISTENCE = 3
        private const val TEMPO_UPDATE_SEC = 0.5f
        // Ширина окна попадания в сетку как доля длительности доли
        private const val GRID_SIGMA_PERIOD_RATIO = 0.07f
        // Вес половины доли относительно самой доли
        private const val HALF_BEAT_WEIGHT = 0.75f
        // Ниже этой уверенности в темпе сетка не применяется
        private const val MIN_CONFIDENCE_FOR_GRID = 0.3f
        private const val BASS_BAND_END = 7
        private const val HIGH_BAND_START = 18
    }

    private val prevBands = FloatArray(TwentyFiveBandVisualizer.SPECTRUM_BAND_COUNT)
    private var hasPrev = false

    private val history = FloatArray(HISTORY_SIZE)
    private var historyPos = 0
    private var historyCount = 0
    private var hopAccumulator = 0f
    private var tempoTimer = 0f
    private val acf = FloatArray(HISTORY_SIZE)

    private var hasTempo = false
    private var sinceBeatSec = 0f
    private var candidateLag = 0
    private var candidateCount = 0

    // Оценённый период темпа (может оказаться удвоенным темпом в эпизодах без явной доли)
    private var tempoPeriodSec: Float = 0.5f

    /**
     * Длительность отображаемой доли, сек: темп выше [MAX_PROMOTED_BPM] показывается через долю,
     * чтобы визуализация не переходила на «двойную скорость».
     */
    val beatPeriodSec: Float
        get() = if (60f / tempoPeriodSec > MAX_PROMOTED_BPM) tempoPeriodSec * 2f else tempoPeriodSec

    /** Уверенность в темпе 0..1. */
    var confidence: Float = 0f
        private set

    val bpm: Float
        get() = 60f / beatPeriodSec

    /**
     * Обработать кадр спектра длительностью [dtSec].
     */
    fun process(bands: FloatArray, dtSec: Float) {
        pushHistory(onsetStrength(bands), dtSec)

        sinceBeatSec += dtSec
        val period = beatPeriodSec
        while (sinceBeatSec >= period) sinceBeatSec -= period

        tempoTimer += dtSec
        if (tempoTimer >= TEMPO_UPDATE_SEC && historyCount >= HISTORY_SIZE / 2) {
            tempoTimer = 0f
            estimateTempo()
            if (hasTempo) estimatePhase()
        }
    }

    /**
     * Вес текущего момента в ритмической сетке: 1 — на доле, [HALF_BEAT_WEIGHT] — на половине доли,
     * около 0 — между ними. Без уверенного темпа сетка не ограничивает (всегда 1).
     */
    fun gridWeight(): Float {
        if (confidence < MIN_CONFIDENCE_FOR_GRID) return 1f
        val period = beatPeriodSec
        val sigma = period * GRID_SIGMA_PERIOD_RATIO
        val toBeat = minOf(sinceBeatSec, period - sinceBeatSec)
        val toHalf = abs(sinceBeatSec - period * 0.5f)
        val onBeat = exp(-0.5f * (toBeat / sigma) * (toBeat / sigma))
        val onHalf = HALF_BEAT_WEIGHT * exp(-0.5f * (toHalf / sigma) * (toHalf / sigma))
        return max(onBeat, onHalf)
    }

    // Гребёнка с периодом доли по истории onset strength: сдвиг с максимальной суммой — момент последней доли
    private fun estimatePhase() {
        val n = historyCount
        val periodHops = (beatPeriodSec / HOP_SEC + 0.5f).toInt()
        if (periodHops < 2 || n < periodHops * 2) return
        val newest = (historyPos - 1 + HISTORY_SIZE) % HISTORY_SIZE
        var bestOffset = 0
        var bestSum = -1f
        for (offset in 0 until periodHops) {
            var sum = 0f
            var back = offset
            while (back < n) {
                // Соседние шаги сетки тоже учитываются: удар может попасть на границу шага
                val idx = (newest - back + HISTORY_SIZE) % HISTORY_SIZE
                sum += history[idx]
                back += periodHops
            }
            if (sum > bestSum) {
                bestSum = sum
                bestOffset = offset
            }
        }
        sinceBeatSec = bestOffset * HOP_SEC + hopAccumulator
    }

    private fun onsetStrength(bands: FloatArray): Float {
        val count = minOf(bands.size, prevBands.size)
        var sum = 0f
        var weightSum = 0f
        for (i in 0 until count) {
            val weight = when {
                i <= BASS_BAND_END -> 1.6f
                i >= HIGH_BAND_START -> 0.6f
                else -> 1.0f
            }
            if (hasPrev) sum += weight * max(0f, bands[i] - prevBands[i])
            weightSum += weight
            prevBands[i] = bands[i]
        }
        hasPrev = true
        return sum / weightSum
    }

    // Приводим кадры анализа (23..50 мс) к равномерной сетке HOP_SEC для автокорреляции.
    // Выборка с удержанием: пустые шаги сетки дали бы ложную периодичность биений
    // между частотой кадров и шагом сетки.
    private fun pushHistory(odf: Float, dtSec: Float) {
        hopAccumulator += dtSec
        while (hopAccumulator >= HOP_SEC) {
            hopAccumulator -= HOP_SEC
            history[historyPos] = odf
            historyPos = (historyPos + 1) % HISTORY_SIZE
            if (historyCount < HISTORY_SIZE) historyCount++
        }
    }

    private fun estimateTempo() {
        val n = historyCount
        val start = (historyPos - n + HISTORY_SIZE) % HISTORY_SIZE
        var mean = 0f
        for (k in 0 until n) mean += history[(start + k) % HISTORY_SIZE]
        mean /= n

        val minLag = (60f / MAX_BPM / HOP_SEC).toInt()
        val maxLag = minOf((60f / MIN_BPM / HOP_SEC).toInt(), n / 2)
        val maxLagComb = minOf(maxLag * 2, n - 1)
        for (lag in 0..maxLagComb) {
            var r = 0f
            for (k in 0 until n - lag) {
                val a = history[(start + k) % HISTORY_SIZE] - mean
                val b = history[(start + k + lag) % HISTORY_SIZE] - mean
                r += a * b
            }
            acf[lag] = r
        }
        val r0 = acf[0]
        if (r0 <= 1e-9f) {
            confidence *= 0.5f
            return
        }

        fun score(lag: Int): Float {
            val comb = acf[lag] + if (lag * 2 <= maxLagComb) 0.5f * acf[lag * 2] else 0f
            // Мягкое предпочтение темпов около PREFERRED_BPM снижает ошибки на октаву
            val bpmAtLag = 60f / (lag * HOP_SEC)
            val octaves = ln(bpmAtLag / PREFERRED_BPM) / ln(2f)
            return comb * exp(-0.5f * octaves * octaves)
        }

        var bestLag = 0
        var bestScore = 0f
        for (lag in max(minLag, 2)..maxLag) {
            val sc = score(lag)
            if (sc > bestScore) {
                bestScore = sc
                bestLag = lag
            }
        }
        if (bestLag == 0) {
            confidence *= 0.7f
            return
        }
        val halfLag = bestLag / 2
        if (halfLag >= max(minLag, 2) &&
            60f / (halfLag * HOP_SEC) <= MAX_PROMOTED_BPM &&
            acf[halfLag] >= HALF_LAG_MIN_RATIO * acf[bestLag]
        ) {
            bestLag = halfLag
            bestScore = score(halfLag)
        } else if (60f / (bestLag * HOP_SEC) > MAX_PROMOTED_BPM && bestLag * 2 <= maxLag &&
            acf[bestLag * 2] >= HALF_LAG_MIN_RATIO * acf[bestLag]
        ) {
            // Удвоенный темп (хай-хэт восьмыми) заменяем на основную долю, если она подтверждена
            bestLag *= 2
            bestScore = score(bestLag)
        }

        var switching = false
        val currentLag = (tempoPeriodSec / HOP_SEC + 0.5f).toInt()
        if (hasTempo && currentLag in (max(minLag, 2) + 1) until maxLag && abs(bestLag - currentLag) > 1) {
            var keptLag = currentLag
            for (lag in currentLag - 1..currentLag + 1) if (score(lag) > score(keptLag)) keptLag = lag
            if (abs(bestLag - candidateLag) <= 1) candidateCount++ else {
                candidateLag = bestLag
                candidateCount = 1
            }
            if (score(keptLag) >= STICKY_SCORE_RATIO * bestScore || candidateCount < SWITCH_PERSISTENCE) {
                bestLag = keptLag
            } else {
                switching = true
                candidateCount = 0
            }
        } else {
            candidateCount = 0
        }

        // Параболическая интерполяция пика для субхоповой точности периода
        val y0 = acf[bestLag - 1]
        val y1 = acf[bestLag]
        val y2 = acf[bestLag + 1]
        val denom = y0 - 2f * y1 + y2
        val shift = if (denom < 0f) (0.5f * (y0 - y2) / denom).coerceIn(-0.5f, 0.5f) else 0f
        val newPeriod = (bestLag + shift) * HOP_SEC

        val newConfidence = (acf[bestLag] / r0 * 2.5f).coerceIn(0f, 1f)
        // Резкие скачки периода принимаем только при высокой уверенности
        val blend = when {
            !hasTempo || switching -> 1f
            else -> 0.35f
        }
        tempoPeriodSec += blend * (newPeriod - tempoPeriodSec)
        hasTempo = true
        confidence += 0.4f * (newConfidence - confidence)
    }

    fun reset() {
        prevBands.fill(0f)
        hasPrev = false
        history.fill(0f)
        historyPos = 0
        historyCount = 0
        hopAccumulator = 0f
        tempoTimer = 0f
        hasTempo = false
        sinceBeatSec = 0f
        candidateLag = 0
        candidateCount = 0
        tempoPeriodSec = 0.5f
        confidence = 0f
    }
}
