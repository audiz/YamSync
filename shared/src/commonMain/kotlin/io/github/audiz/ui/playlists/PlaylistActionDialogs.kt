package io.github.audiz.ui.playlists

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.audiz.SearchViewModel
import io.github.audiz.getPlatformPresetDirectories
import io.github.audiz.isPlatformPickerSupported
import io.github.audiz.launchAudioOrPlaylistFilePicker
import io.github.audiz.launchDirectoryPicker
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PlaylistInfo

/**
 * 📁 Диалог добавления локального источника музыки (папка, аудиофайл или M3U).
 */
@Composable
internal fun AddSourceDialog(
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    var sourcePathInput by remember { mutableStateOf("") }
    var sourceCustomName by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Источник музыки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Укажите папку с музыкой, отдельный файл (.mp3, .flac, .m4a) или плейлист (.m3u / .m3u8):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val presets = remember { getPlatformPresetDirectories() }
                if (presets.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { (label, path) ->
                            Surface(
                                onClick = {
                                    sourcePathInput = path
                                    val folderName = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                                    sourceCustomName = if (folderName.isNotBlank()) folderName else label.substringAfter(' ').trim()
                                    sourceError = null
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sourcePathInput == path) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sourcePathInput == path) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                if (isPlatformPickerSupported) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                launchDirectoryPicker { picked ->
                                    if (!picked.isNullOrBlank()) {
                                        sourcePathInput = picked
                                        val folderName = picked.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                                        sourceCustomName = folderName
                                        sourceError = null
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать папку", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = {
                                launchAudioOrPlaylistFilePicker { picked ->
                                    if (!picked.isNullOrBlank()) {
                                        sourcePathInput = picked
                                        val fileName = picked.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                                        sourceCustomName = fileName
                                        sourceError = null
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(Icons.Filled.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать файл", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = sourcePathInput,
                    onValueChange = {
                        sourcePathInput = it
                        sourceError = null
                    },
                    label = { Text("Путь к папке или файлу") },
                    placeholder = { Text("/home/user/Music или C:\\Music\\song.mp3") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrBlank()) {
                                    sourcePathInput = clipText.trim()
                                    sourceError = null
                                }
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Вставить из буфера")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = sourceCustomName,
                    onValueChange = { sourceCustomName = it },
                    label = { Text("Название источника (необязательно)") },
                    placeholder = { Text("Моя папка, Любимый альбом...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val error = sourceError ?: viewModel.localMediaStatusMessage
                if (!error.isNullOrBlank() && (error.startsWith("❌") || error.startsWith("⚠️"))) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val path = sourcePathInput.trim()
                    if (path.isNotBlank()) {
                        val added = viewModel.addCustomSource(
                            path = path,
                            customName = sourceCustomName.trim().ifBlank { null }
                        )
                        if (added != null) {
                            onDismiss()
                        } else {
                            sourceError = viewModel.localMediaStatusMessage ?: "Не удалось добавить источник"
                        }
                    }
                },
                enabled = sourcePathInput.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * ➕ Диалог создания нового локального плейлиста.
 */
@Composable
internal fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, desc: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый локальный плейлист") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    placeholder = { Text("Например: В дорогу, Любимый рок...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Описание (необязательно)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onCreate(title.trim(), desc.trim())
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * ✏️ Диалог переименования локального плейлиста.
 */
@Composable
internal fun RenameLocalPlaylistDialog(
    playlist: LocalPlaylist,
    onDismiss: () -> Unit,
    onConfirm: (newTitle: String) -> Unit
) {
    var title by remember { mutableStateOf(playlist.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать плейлист") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Новое название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim())
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * 🗑️ Диалог подтверждения удаления локального плейлиста.
 */
@Composable
internal fun DeletePlaylistConfirmDialog(
    playlist: LocalPlaylist,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Удалить плейлист?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Вы действительно хотите удалить плейлист «${playlist.title}»?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Скачанные треки этого плейлиста также будут удалены с устройства. Файлы из вашей личной медиатеки устройства затронуты не будут.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Отмена")
            }
        }
    )
}

/**
 * ☁️ Диалог переименования плейлиста Яндекс Музыки.
 */
@Composable
internal fun RenameYandexPlaylistDialog(
    playlist: PlaylistInfo,
    onDismiss: () -> Unit,
    onConfirm: (newTitle: String) -> Unit
) {
    var title by remember { mutableStateOf(playlist.title) }
    var isRenaming by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isRenaming) onDismiss()
        },
        title = { Text("Переименовать плейлист Яндекс") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Новое название") },
                    singleLine = true,
                    enabled = !isRenaming,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isRenaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Сохранение в Яндекс Музыке...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        isRenaming = true
                        onConfirm(title.trim())
                    }
                },
                enabled = title.isNotBlank() && !isRenaming
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRenaming
            ) {
                Text("Отмена")
            }
        }
    )
}
