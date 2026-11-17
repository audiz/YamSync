package io.github.audiz.support

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

internal actual fun currentUsageDate(): String = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = "yyyy-MM-dd"
}.stringFromDate(NSDate())
