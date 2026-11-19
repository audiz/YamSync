package io.github.audiz.ui

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Общая сетка кадров (~30 fps) для декоративных анимаций: визуализаторов спектра и «дыхания» подсветки.
 *
 * Окно Compose Desktop перерисовывается целиком, поэтому каждый запрошенный кадр стоит
 * одинаково дорого. Анимации, ждущие кадр через [DecorFrameTicker], пропускают каждый второй
 * vsync и попадают в одни и те же кадры, вместо того чтобы держать окно на 60 fps.
 */
private const val DECOR_FRAME_PERIOD_NS = 33_333_333L

class DecorFrameTicker {
    private var lastFrameNs = 0L
    private var lastFrameMark: TimeMark? = null

    /**
     * Дождаться следующего кадра сетки и вызвать [onFrame] внутри него (изменения состояния
     * попадают в этот же кадр). [onFrame] получает время кадра и интервал от предыдущего в секундах.
     */
    suspend fun awaitFrame(onFrame: (frameNs: Long, dtSeconds: Float) -> Unit) {
        val mark = lastFrameMark
        if (lastFrameNs > 0L && mark != null) {
            val nextSlotNs = (lastFrameNs / DECOR_FRAME_PERIOD_NS + 1) * DECOR_FRAME_PERIOD_NS
            val elapsedNs = mark.elapsedNow().inWholeNanoseconds
            // Ждём начала следующего слота; ближайший после него vsync и станет кадром
            val waitMs = (nextSlotNs - lastFrameNs - elapsedNs + 999_999L) / 1_000_000L
            if (waitMs > 0L) delay(waitMs)
        }
        withFrameNanos { frameNs ->
            val dt = if (lastFrameNs > 0L) (frameNs - lastFrameNs) / 1_000_000_000f else 1f / 30f
            lastFrameNs = frameNs
            lastFrameMark = TimeSource.Monotonic.markNow()
            onFrame(frameNs, dt)
        }
    }
}
