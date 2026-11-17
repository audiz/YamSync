package io.github.audiz.support

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun currentUsageDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
