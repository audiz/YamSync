package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
        modifier = Modifier.width(width).heightIn(max = 280.dp),
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
                val text = buildAnnotatedString {
                    val match = suggestion.title.indexOf(query.trim(), ignoreCase = true)
                    if (match >= 0 && query.isNotBlank()) {
                        append(suggestion.title.substring(0, match))
                        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) {
                            append(suggestion.title.substring(match, match + query.trim().length))
                        }
                        append(suggestion.title.substring(match + query.trim().length))
                    } else append(suggestion.title)
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
                            SearchSuggestionType.ARTIST -> Icons.Filled.Person
                            SearchSuggestionType.TRACK -> Icons.Filled.MusicNote
                            SearchSuggestionType.QUERY -> Icons.Filled.Search
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = suggestion.type.label,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
