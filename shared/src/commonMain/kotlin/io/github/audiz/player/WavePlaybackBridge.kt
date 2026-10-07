package io.github.audiz.player

import io.github.audiz.TrackPlaySource
import io.github.audiz.models.FullTrackInfo

/**
 * 🌊 Мост взаимодействия между стейт-машиной воспроизведения и радиорежимом «Моя Волна».
 * Инкапсулирует получение очереди волны, управление переходами и отправку фидбеков.
 */
interface WavePlaybackBridge {
    /** Активен ли в данный момент режим «Моя Волна» */
    val isWaveMode: Boolean

    /** Список треков текущей пачки Волны */
    val waveTracks: List<FullTrackInfo>

    /** Индекс текущего играющего трека в пачке Волны */
    val waveCurrentIndex: Int

    /** Воспроизведение трека Волны по индексу */
    fun playWaveTrack(index: Int = 0, source: TrackPlaySource = TrackPlaySource.PLAY, crossfadeMs: Long = 0L)

    /** Переход к следующему треку в Моей Волне */
    fun playNextWaveTrack(crossfadeMs: Long = 0L)

    /** Переход к предыдущему треку в Моей Волне */
    fun playPrevWaveTrack()

    /** Обработка завершения трека Волны (с учетом плавного сведения/кроссфейда) */
    fun onWaveTrackFinished(crossfadeMs: Long = 0L)

    /** Запуск воспроизведения стартовой Волны */
    fun loadInitialWave(autoPlay: Boolean = false, source: TrackPlaySource = TrackPlaySource.PLAY)

    /** Получение запланированного следующего кандидата для предзагрузки */
    fun getNextWaveTrackCandidate(): FullTrackInfo?
}
