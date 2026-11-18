package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.audiz.search.SearchSuggestion
import io.github.audiz.search.SearchSuggestionType

@Composable
internal fun SearchSuggestionsMenu(
    expanded: Boolean,
    suggestions: List<SearchSuggestion>,
    query: String,
    selectedIndex: Int,
    width: Dp,
    onDismiss: () -> Unit,
    onSelect: (SearchSuggestion) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).heightIn(max = 320.dp),
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        properties = PopupProperties(focusable = false)
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            key(suggestion.type, suggestion.artist?.id, suggestion.track?.id, suggestion.title) {
                val interactions = remember { MutableInteractionSource() }
                val hovered by interactions.collectIsHoveredAsState()
                val highlight = MaterialTheme.colorScheme.primary
                val titleText = remember(suggestion.title, query, highlight) {
                    buildHighlightedText(suggestion.title, query, highlight)
                }
                val subtitleText = remember(suggestion.subtitle, query, highlight) {
                    suggestion.subtitle?.let { buildHighlightedText(it, query, highlight) }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp)
                        .fillMaxWidth()
                        .background(
                            if (index == selectedIndex || hovered) highlight.copy(alpha = 0.12f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(interactionSource = interactions, indication = null) { onSelect(suggestion) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = when (suggestion.type) {
                            SearchSuggestionType.LOCAL_TRACK -> Icons.Filled.DownloadDone
                            SearchSuggestionType.ARTIST -> Icons.Filled.Person
                            SearchSuggestionType.TRACK -> Icons.Filled.MusicNote
                            SearchSuggestionType.QUERY -> Icons.Filled.Search
                        },
                        contentDescription = null,
                        tint = if (suggestion.type == SearchSuggestionType.LOCAL_TRACK) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = titleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (subtitleText != null) {
                            Text(
                                text = subtitleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Text(
                        text = suggestion.type.label,
                        modifier = Modifier.background(
                            if (suggestion.type == SearchSuggestionType.LOCAL_TRACK) {
                                Color(0xFF10B981).copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(5.dp)
                        ).padding(horizontal = 6.dp, vertical = 3.dp),
                        color = if (suggestion.type == SearchSuggestionType.LOCAL_TRACK) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun buildHighlightedText(text: String, query: String, highlight: Color): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return AnnotatedString(text)

    val match = text.indexOf(trimmed, ignoreCase = true)
    if (match >= 0) {
        return buildAnnotatedString {
            append(text.substring(0, match))
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(match, match + trimmed.length))
            }
            append(text.substring(match + trimmed.length))
        }
    }

    val words = trimmed.split(" ", "-", "_").filter { it.isNotBlank() }
    if (words.size > 1) {
        val lower = text.lowercase()
        val intervals = mutableListOf<IntRange>()
        for (w in words) {
            val wLower = w.lowercase()
            var start = 0
            while (start < lower.length) {
                val idx = lower.indexOf(wLower, start)
                if (idx < 0) break
                intervals.add(idx until (idx + w.length))
                start = idx + w.length
            }
        }
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            val merged = mutableListOf<IntRange>()
            var current = intervals[0]
            for (i in 1 until intervals.size) {
                val next = intervals[i]
                if (next.first <= current.last + 1) {
                    current = current.first..maxOf(current.last, next.last)
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)

            return buildAnnotatedString {
                var lastIndex = 0
                for (range in merged) {
                    if (range.first > lastIndex) {
                        append(text.substring(lastIndex, range.first))
                    }
                    withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(range.first, minOf(range.last + 1, text.length)))
                    }
                    lastIndex = minOf(range.last + 1, text.length)
                }
                if (lastIndex < text.length) {
                    append(text.substring(lastIndex))
                }
            }
        }
    }

    return AnnotatedString(text)
}
