package io.github.audiz.support

import io.github.audiz.AppConfigKeys
import io.github.audiz.DispatcherIO
import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class StarPromptManager(
    private val read: () -> String? = { loadAppConfig(AppConfigKeys.STAR_PROMPT_STATE) },
    private val write: (String) -> Unit = { saveAppConfig(AppConfigKeys.STAR_PROMPT_STATE, it) },
    private val today: () -> String = ::currentUsageDate
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<StarPromptState?>(null)
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun update(transform: (StarPromptState) -> StarPromptState) = mutex.withLock {
        withContext(NonCancellable + DispatcherIO) {
            val previous = mutableState.value ?: read()?.let {
                try {
                    json.decodeFromString<StarPromptState>(it)
                } catch (_: IllegalArgumentException) {
                    // Do not repeat offers when a previously saved preference cannot be read.
                    StarPromptState(completed = true)
                }
            } ?: StarPromptState()
            val next = transform(previous)
            if (next != previous) write(json.encodeToString(next))
            mutableState.value = next
        }
    }

    suspend fun recordActiveDay() = update { it.recordDay(today()) }

    suspend fun claimShow(): Boolean {
        var claimed = false
        update {
            claimed = it.eligible
            it.shown()
        }
        return claimed
    }

    suspend fun postpone() = update { it.copy(nextPromptDay = it.activeDaysCount + 4) }

    suspend fun complete() = update { it.copy(completed = true) }
}
