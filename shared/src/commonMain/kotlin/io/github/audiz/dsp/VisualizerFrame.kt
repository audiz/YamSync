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

    // 🎛️ 25 частотных полос (20 Гц .. 20.5 кГц, логарифмическая шкала Winamp Pro)
    val FREQ_RANGES_25 = arrayOf(
        20.0f to 26.4f,      // 0: ~23 Гц (Суб-бас)
        26.4f to 34.8f,      // 1: ~30 Гц
        34.8f to 46.0f,      // 2: ~40 Гц
        46.0f to 60.7f,      // 3: ~53 Гц (Бочка / Kick)
        60.7f to 80.1f,      // 4: ~70 Гц
        80.1f to 105.7f,     // 5: ~92 Гц
        105.7f to 139.5f,    // 6: ~122 Гц (Бас-гитара)
        139.5f to 184.0f,    // 7: ~160 Гц
        184.0f to 242.9f,    // 8: ~212 Гц
        242.9f to 320.5f,    // 9: ~280 Гц (Теплота / Низкая середина)
        320.5f to 422.9f,    // 10: ~370 Гц
        422.9f to 558.0f,    // 11: ~488 Гц
        558.0f to 736.3f,    // 12: ~644 Гц (Вокал / Тело)
        736.3f to 971.6f,    // 13: ~850 Гц
        971.6f to 1282.0f,   // 14: ~1.12 кГц
        1282.0f to 1691.7f,  // 15: ~1.47 кГц
        1691.7f to 2232.2f,  // 16: ~1.95 кГц (Разборчивость)
        2232.2f to 2945.4f,  // 17: ~2.57 кГц
        2945.4f to 3886.5f,  // 18: ~3.39 кГц (Атака малого барабана)
        3886.5f to 5128.2f,  // 19: ~4.48 кГц
        5128.2f to 6766.8f,  // 20: ~5.90 кГц (Яркость)
        6766.8f to 8928.9f,  // 21: ~7.80 кГц
        8928.9f to 11781.8f, // 22: ~10.3 кГц (Хай-хеты)
        11781.8f to 15546.2f,// 23: ~13.5 кГц (Тарелки)
        15546.2f to 20500.0f // 24: ~17.8 кГц (Воздух)
    )
}
