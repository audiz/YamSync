package io.github.audiz.dsp

/**
 * 📦 Кадр спектрального визуализатора Winamp Pro для отрисовки в UI
 * @property levels Текущие высоты столбиков (0.0 .. 1.0)
 * @property peakCaps Парящие пиковые засечки (Peak Caps) (0.0 .. 1.0)
 */
data class VisualizerFrame(
    val levels: FloatArray,
    val peakCaps: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerFrame) return false
        if (!levels.contentEquals(other.levels)) return false
        return peakCaps.contentEquals(other.peakCaps)
    }

    override fun hashCode(): Int {
        var result = levels.contentHashCode()
        result = 31 * result + peakCaps.contentHashCode()
        return result
    }
}

/**
 * ⚙️ Общие базовые константы спектральной баллистики и визуализации
 */
internal object VisualizerConstants {
    const val RESTING_LEVEL = 0.15f

    // 5-полосная и 25-полосная студийная баллистика Winamp Pro
    const val BAR_HOLD_SEC = 0.060f       // 60 мс фиксация удара бочки/баса
    const val BAR_GRAVITY = 4.2f          // Ускорение падения столбиков (g ≈ 4.2 / s²)
    const val PEAK_CAP_HOLD_SEC = 0.280f  // 280 мс парение пиковой засечки
    const val PEAK_CAP_GRAVITY = 2.8f     // Ускорение падения пиковой засечки

    // Базовые границы адаптивной физики (Медленный темп <-> Быстрый темп)
    const val SLOW_BAR_HOLD_SEC = 0.095f    // 95 мс мягкое зависание на плавных частях
    const val FAST_BAR_HOLD_SEC = 0.030f    // 30 мс четкая отсечка на быстром ритме
    const val SLOW_BAR_GRAVITY = 1.85f      // Тягучее плавное падение на интро/эмбиенте
    const val FAST_BAR_GRAVITY = 6.40f      // Хлесткий возврат на быстром темпе/дропе

    const val SLOW_PEAK_HOLD_SEC = 0.380f   // Долгое парение засечек на медленном темпе
    const val FAST_PEAK_HOLD_SEC = 0.210f   // Быстрый сброс засечек на активном бите
    const val SLOW_PEAK_GRAVITY = 1.60f
    const val FAST_PEAK_GRAVITY = 3.60f
}
