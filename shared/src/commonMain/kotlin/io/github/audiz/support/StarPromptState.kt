package io.github.audiz.support

import kotlinx.serialization.Serializable

const val PROJECT_GITHUB_URL = "https://github.com/audiz/YamSync"

@Serializable
internal data class StarPromptState(
    val lastActiveDate: String = "",
    val activeDaysCount: Int = 0,
    val nextPromptDay: Int = 3,
    val shownCount: Int = 0,
    val completed: Boolean = false
) {
    val eligible: Boolean
        get() = !completed && shownCount < 2 && activeDaysCount >= nextPromptDay

    fun recordDay(date: String): StarPromptState =
        if (date > lastActiveDate) copy(lastActiveDate = date, activeDaysCount = activeDaysCount + 1)
        else this

    // Reserve the reminder when showing, so a restart cannot repeat the same offer.
    fun shown(): StarPromptState = if (eligible) {
        copy(shownCount = shownCount + 1, nextPromptDay = activeDaysCount + 4)
    } else this
}

/** Local calendar date in sortable yyyy-MM-dd format. */
internal expect fun currentUsageDate(): String
