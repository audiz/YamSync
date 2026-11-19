package io.github.audiz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.SearchViewModel
import io.github.audiz.models.ThematicWavePreset

@Composable
internal fun WaveThemesDialog(viewModel: SearchViewModel, onDismiss: () -> Unit) {
    val recent = viewModel.recentThematicWaves.take(3)
    val recentSeeds = recent.map { it.seeds }.toSet()
    val catalog = viewModel.thematicWavePresets.filterNot { it.seeds in recentSeeds }
    val listState = rememberLazyListState()
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 540.dp
            Card(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp).fillMaxHeight(0.9f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(if (compact) 10.dp else 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Темы волны", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.Filled.Close, "Закрыть")
                        }
                    }
                    Text("Выберите тему, чтобы слушать волну", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item(key = "personal") {
                                WaveThemeItem(title = "Персональная", subtitle = "Под ваш вкус", theme = null,
                                    viewModel = viewModel, onDismiss = onDismiss)
                            }
                            if (recent.isNotEmpty()) {
                                item(key = "recent_header") { WaveThemesHeading("Недавно слушали") }
                                items(recent, key = { "recent_${it.seeds.joinToString()}" }) { theme ->
                                    WaveThemeItem(title = theme.title, theme = theme, viewModel = viewModel, onDismiss = onDismiss)
                                }
                            }
                            item(key = "catalog_header") { WaveThemesHeading("Все жанры") }
                            items(catalog, key = { "genre_${it.seeds.joinToString()}" }) { theme ->
                                WaveThemeItem(title = theme.title, theme = theme, viewModel = viewModel, onDismiss = onDismiss)
                            }
                        }
                        PlatformScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveThemesHeading(text: String) {
    Text(text, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WaveThemeItem(
    title: String,
    subtitle: String? = null,
    theme: ThematicWavePreset?,
    viewModel: SearchViewModel,
    onDismiss: () -> Unit
) {
    val selected = viewModel.isWaveMode && viewModel.currentWaveSeeds == theme?.seeds.orEmpty()
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !viewModel.isWaveLoading, role = Role.Button) {
                viewModel.selectWaveTheme(theme)
                onDismiss()
            }.pointerHoverIcon(PointerIcon.Hand),
        colors = ListItemDefaults.colors(containerColor = if (selected) accent.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(Icons.Filled.Waves, null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(if (selected) Icons.Filled.Check else Icons.Filled.PlayArrow, null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    )
}
