package io.github.audiz.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Вызывается из одного потока вместе с публикацией системных метаданных. */
internal class NowPlayingArtworkLoader<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> T?,
    private val publish: (T) -> Unit,
    private val retryDelayMs: Long = 1_000L,
    private val cacheLimit: Int = 8
) {
    private val cache = linkedMapOf<String, T>()
    private var trackKey: String? = null
    private var sources = emptyList<String>()
    private var artwork: T? = null
    private var generation = 0L
    private var job: Job? = null

    fun update(key: String, requestedSources: List<String>): T? {
        val effectiveSources = requestedSources.filter { it.isNotBlank() }.distinct()
            .ifEmpty { if (key == trackKey) sources else emptyList() }
        if (key == trackKey && effectiveSources == sources) return artwork
        job?.cancel()
        val request = ++generation
        trackKey = key
        sources = effectiveSources
        artwork = effectiveSources.firstNotNullOfOrNull { source ->
            cache.remove(source)?.also { cache[source] = it }
        }
        if (artwork == null && effectiveSources.isNotEmpty()) {
            job = scope.launch {
                repeat(3) { attempt ->
                    if (attempt > 0) delay(retryDelayMs * attempt)
                    for (source in effectiveSources) {
                        val image = try {
                            load(source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        if (request != generation) return@launch
                        if (image != null) {
                            cache[source] = image
                            while (cache.size > cacheLimit) cache.remove(cache.keys.first())
                            artwork = image
                            publish(image)
                            return@launch
                        }
                    }
                }
            }
        }
        return artwork
    }

    fun clear() {
        ++generation
        job?.cancel()
        job = null
        trackKey = null
        sources = emptyList()
        artwork = null
    }
}
