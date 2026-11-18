package io.github.audiz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.SearchViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpNextDialog(viewModel: SearchViewModel, onOpenPlaylist: () -> Unit, onDismiss: () -> Unit) {
    val state = viewModel.upNextState
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 540.dp
            Card(Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(max = maxHeight * 0.9f)) {
                Column(Modifier.padding(if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Далее · ${state.pending.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.Default.Close, "Закрыть")
                        }
                    }
                    Text(
                        "Сейчас: ${viewModel.playerTrackTitle.ifBlank { "Ничего не играет" }}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
                    )
                    Text("Треки очереди играют по порядку", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.pending.isEmpty()) {
                        Text("Очередь пуста. Добавляйте треки через кнопку «Добавить в плейлист».",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(state.pending, key = { _, entry -> entry.key }) { index, entry ->
                                ListItem(
                                    headlineContent = { Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(entry.track.artists.joinToString { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = {
                                        FlowRow(Modifier.widthIn(max = 112.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), maxItemsInEachRow = 3) {
                                            IconButton(onClick = { viewModel.moveUpNext(entry.key, -1) }, enabled = index > 0,
                                                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)) {
                                                Icon(Icons.Default.ArrowUpward, "Раньше")
                                            }
                                            IconButton(onClick = { viewModel.moveUpNext(entry.key, 1) }, enabled = index < state.pending.lastIndex,
                                                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)) {
                                                Icon(Icons.Default.ArrowDownward, "Позже")
                                            }
                                            IconButton(onClick = { viewModel.removeFromUpNext(entry.key) },
                                                modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)) {
                                                Icon(Icons.Default.Close, "Удалить из очереди")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Text("Затем: ${viewModel.upNextContinuationTitle.ifBlank { "исходный плейлист" }}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = viewModel::clearUpNext, enabled = state.pending.isNotEmpty(),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Очистить очередь") }
                        if (viewModel.hasPlaybackQueue) {
                            TextButton(onClick = onOpenPlaylist, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                                Text("Исходный плейлист")
                            }
                        }
                    }
                }
            }
        }
    }
}
