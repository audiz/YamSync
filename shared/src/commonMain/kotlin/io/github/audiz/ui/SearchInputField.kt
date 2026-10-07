package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.audiz.getPlatform

@Composable
fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    isLoading: Boolean,
    onClear: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val parsedTrackId = remember(query) { io.github.audiz.DeepLinkHandler.extractTrackId(query) }
    val isLink = parsedTrackId != null
    val isMobile = remember { getPlatform().isMobile }

    val handleSearch = {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (query.isNotBlank() && !isLoading) {
            onSearchClick()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .border(
                    width = if (isFocused || isLink) 1.5.dp else 1.dp,
                    color = if (isLink) MaterialTheme.colorScheme.primary else if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isLink) Icons.Filled.Link else Icons.Filled.Search,
                contentDescription = if (isLink) "Ссылка на трек" else "Поиск",
                tint = if (isLink || isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Поиск: трек, артист или ссылка (yamsync://)...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = interactionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { handleSearch() }
                    )
                )
            }

            if (query.isEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    if (text.isNotBlank()) {
                                        onQueryChange(text)
                                    }
                                }
                            }
                        )
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "Вставить из буфера",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else if (onClear != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClear
                        )
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Очистить поиск",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        val showSearchButton = !isMobile || !isFocused || query.isNotBlank()
        if (showSearchButton) {
            Button(
                onClick = handleSearch,
                enabled = query.isNotBlank() && !isLoading,
                modifier = Modifier
                    .height(42.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isLink) Icons.Filled.PlayArrow else Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            isLoading -> "Загрузка..."
                            isLink -> "Включить"
                            else -> "Найти"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (isFocused) {
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Скрыть клавиатуру",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else if (onSettingsClick != null) {
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onSettingsClick()
                },
                modifier = Modifier
                    .size(42.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
