package io.github.audiz.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.TracksListOrigin
import io.github.audiz.SearchViewModel
import io.github.audiz.sync.YamSyncConnectionState
import io.github.audiz.isPlatformPickerSupported
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PersonalPlaylistItemData
import io.github.audiz.models.ThematicWavePreset
import io.github.audiz.pickDirectory

/**
 * 🏠 Главный экран-хаб приложения (Home Hub).
 * Адаптирует сетки и прокручиваемые подборки под доступную ширину экрана:
 * 1. Герой-баннер «Моя Волна» (чипы стилей переносятся на новую строку).
 * 2. Плитки быстрого доступа (перенос 2x2 на смартфонах).
 * 3. Карточка быстрого продолжения последней сессии (если есть).
 * 4. Персональные подборки Яндекса (горизонтальная лента).
 * 5. Локальные плейлисты и папки (сетка на всю ширину).
 */
@Composable
fun HomeHub(
    viewModel: SearchViewModel,
    onOpenMediaLibrary: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 540.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 120.dp), // Отступ снизу для плавающего плеера
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================================================
            // 🌊 СЕКЦИЯ 1: ГЕРОЙ-БАННЕР «МОЯ ВОЛНА»
            // ==================================================
            WaveHeroCard(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth()
            )

            // ==================================================
            // ⚡ СЕКЦИЯ 2: ПЛИТКИ БЫСТРОГО ДОСТУПА
            // ==================================================
            QuickAccessSection(
                viewModel = viewModel,
                onOpenMediaLibrary = onOpenMediaLibrary,
                onOpenDevices = onOpenDevices,
                isWide = isWide,
                modifier = Modifier.fillMaxWidth()
            )

            // ==================================================
            // 🕒 СЕКЦИЯ 3: ПРОДОЛЖИТЬ ВОСПРОИЗВЕДЕНИЕ (если есть сессия и плеер на паузе/стопе)
            // ==================================================
            val lastSession = viewModel.playbackSessionManager.currentSession
            if (lastSession != null && !viewModel.playerIsPlaying) {
                ResumeSessionCard(
                    session = lastSession,
                    onResume = { viewModel.resumeLastSession(lastSession) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ==================================================
            // ✨ СЕКЦИЯ 4: ПЕРСОНАЛЬНЫЕ ПОДБОРКИ (Плейлист дня, Премьера, Дежавю...)
            // ==================================================
            if (viewModel.personalPlaylists.isNotEmpty()) {
                PersonalPlaylistsSection(
                    items = viewModel.personalPlaylists,
                    onSelect = { item ->
                        val playlist = item.playlist!!
                        if (playlist.playlistUuid.isNotBlank()) {
                            viewModel.loadPlaylistByUuid(playlist.playlistUuid, item.displayTitle)
                        } else {
                            viewModel.loadPlaylistTracks(playlist.uid, playlist.kind, item.displayTitle)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ==================================================
            // 📂 СЕКЦИЯ 5: МОИ ПАПКИ И ЛОКАЛЬНЫЕ ПЛЕЙЛИСТЫ
            // ==================================================
            if (viewModel.localPlaylists.isNotEmpty() || viewModel.customMediaSources.isNotEmpty() || isPlatformPickerSupported) {
                LocalLibrarySection(
                    localPlaylists = viewModel.localPlaylists,
                    customSources = viewModel.customMediaSources,
                    onOpenLocalPlaylist = { viewModel.openLocalPlaylist(it) },
                    onOpenFolder = { path, name, src ->
                        viewModel.openFolderPlaylist(path, name, src, isRecursive = true, origin = TracksListOrigin.HOME)
                    },
                    onAddFolder = {
                        val picked = pickDirectory()
                        if (!picked.isNullOrBlank()) {
                            viewModel.addCustomSource(picked)
                        }
                    },
                    onOpenMediaLibrary = onOpenMediaLibrary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ============================================================================
// 🌊 1. КАРТОЧКА «МОЯ ВОЛНА» (Чипы с автоматическим переносом FlowRow)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaveHeroCard(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    var showThemes by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val personalSelected = viewModel.isWaveMode && viewModel.currentWaveSeeds.isEmpty()
    val personalStatus = when {
        personalSelected && viewModel.isWaveLoading -> "Загрузка…"
        personalSelected && viewModel.playerIsPlaying && !viewModel.playerIsPaused -> "Выбрана"
        personalSelected && viewModel.playerIsPaused -> "Продолжить"
        else -> "Слушать"
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (viewModel.isWaveMode) accent.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 340.dp
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.08f), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))))
                    .padding(if (compact) 10.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Waves, null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Моя волна", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (viewModel.isWaveLoading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { showThemes = true }
                            .pointerHoverIcon(PointerIcon.Hand).heightIn(min = 36.dp).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Все темы", color = accent, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ChevronRight, null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
                Surface(
                    onClick = { viewModel.selectWaveTheme() },
                    enabled = !viewModel.isWaveLoading,
                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(10.dp),
                    color = if (personalSelected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, if (personalSelected) accent.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.StarBorder, null, tint = accent, modifier = Modifier.size(20.dp))
                        Text("Персональная", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(personalStatus, color = if (personalSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (viewModel.recentThematicWaves.isNotEmpty()) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            viewModel.recentThematicWaves.take(3).forEach { theme ->
                                val selected = viewModel.isWaveMode && viewModel.currentWaveSeeds == theme.seeds
                                Surface(
                                    onClick = { viewModel.selectWaveTheme(theme) },
                                    enabled = !viewModel.isWaveLoading,
                                    modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ) {
                                    Row(Modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (selected) Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.size(14.dp))
                                        Text(theme.title, style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showThemes) WaveThemesDialog(viewModel = viewModel, onDismiss = { showThemes = false })
}

// ============================================================================
// ⚡ 2. СЕКЦИЯ БЫСТРОГО ДОСТУПА (Spotify Style с переносом на след. строку)
// ============================================================================

@Composable
private fun QuickAccessSection(
    viewModel: SearchViewModel,
    onOpenMediaLibrary: () -> Unit,
    onOpenDevices: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier
) {
    val syncState = viewModel.yamSyncManager.connectionState
    // Точка статуса: зелёная — связано, янтарная — ожидание/связывание, серая — нет соединения
    val devicesStatusColor = when (syncState) {
        is YamSyncConnectionState.Connected, is YamSyncConnectionState.Syncing -> Color(0xFF2DD881)
        is YamSyncConnectionState.Hosting, is YamSyncConnectionState.Connecting -> Color(0xFFF59E0B)
        is YamSyncConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val devicesTile: @Composable (Modifier) -> Unit = { tileModifier ->
        QuickAccessTile(
            title = "Устройства",
            icon = Icons.Filled.Devices,
            accentColor = Color(0xFF2DD881),
            onClick = onOpenDevices,
            statusColor = devicesStatusColor,
            modifier = tileModifier
        )
    }

    Column(modifier = modifier) {
        Text(
            text = "Быстрый доступ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickAccessTile(
                    title = "Скачанное",
                    icon = Icons.Filled.DownloadDone,
                    accentColor = Color(0xFF10B981), // Emerald
                    onClick = { viewModel.loadDownloadedTracksPlaylist() },
                    modifier = Modifier.weight(1f)
                )
                QuickAccessTile(
                    title = "Любимые треки",
                    icon = Icons.Filled.Favorite,
                    accentColor = Color(0xFFEF4444), // Rose
                    onClick = { viewModel.loadLikesPlaylist() },
                    modifier = Modifier.weight(1f)
                )
                QuickAccessTile(
                    title = "Медиатека",
                    icon = Icons.Filled.LibraryMusic,
                    accentColor = Color(0xFF3B82F6), // Blue
                    onClick = onOpenMediaLibrary,
                    modifier = Modifier.weight(1f)
                )
                QuickAccessTile(
                    title = "История",
                    icon = Icons.Filled.History,
                    accentColor = Color(0xFFF59E0B), // Amber
                    onClick = { viewModel.loadHistory() },
                    modifier = Modifier.weight(1f)
                )
                devicesTile(Modifier.weight(1f))
            }
        } else {
            // 2 колонки на мобильных — перенос на следующую строку
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickAccessTile(
                        title = "Скачанное",
                        icon = Icons.Filled.DownloadDone,
                        accentColor = Color(0xFF10B981),
                        onClick = { viewModel.loadDownloadedTracksPlaylist() },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAccessTile(
                        title = "Любимые треки",
                        icon = Icons.Filled.Favorite,
                        accentColor = Color(0xFFEF4444),
                        onClick = { viewModel.loadLikesPlaylist() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickAccessTile(
                        title = "Медиатека",
                        icon = Icons.Filled.LibraryMusic,
                        accentColor = Color(0xFF3B82F6),
                        onClick = onOpenMediaLibrary,
                        modifier = Modifier.weight(1f)
                    )
                    QuickAccessTile(
                        title = "История",
                        icon = Icons.Filled.History,
                        accentColor = Color(0xFFF59E0B),
                        onClick = { viewModel.loadHistory() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    devicesTile(Modifier.weight(1f))
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickAccessTile(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color? = null
) {
    Card(
        modifier = modifier
            .height(46.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (statusColor != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
            }
        }
    }
}

// ============================================================================
// 🕒 3. КАРТОЧКА ПРОДОЛЖЕНИЯ СЕССИИ (Компактная плашка)
// ============================================================================

@Composable
private fun ResumeSessionCard(
    session: LastPlaybackSession,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onResume),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Продолжить: ${session.title ?: "Последний плейлист"}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!session.lastTrackTitle.isNullOrBlank()) {
                    Text(
                        text = "${session.lastTrackTitle}${if (!session.lastArtistName.isNullOrBlank()) " • ${session.lastArtistName}" else ""}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            FilledTonalButton(
                onClick = onResume,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text("Слушать", fontSize = 11.sp)
            }
        }
    }
}

// ============================================================================
// ✨ 4. ПЕРСОНАЛЬНЫЕ ПОДБОРКИ ЯНДЕКСА (горизонтальная лента)
// ============================================================================

@Composable
private fun PersonalPlaylistsSection(
    items: List<PersonalPlaylistItemData>,
    onSelect: (PersonalPlaylistItemData) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleItems = items.filter { it.playlist?.canOpen == true }
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Персональные подборки",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gap = 10.dp
            val preferredCardWidth = 142.dp
            val allCardsWidth = preferredCardWidth * visibleItems.size +
                gap * (visibleItems.size - 1).coerceAtLeast(0)
            // При прокрутке оставляем видимыми 32 dp следующей карточки.
            val fullCards = when {
                visibleItems.size <= 2 -> 1
                maxWidth < 540.dp -> 2
                else -> ((maxWidth - 32.dp) / (preferredCardWidth + gap)).toInt().coerceAtLeast(1)
            }
            val cardWidth = if (allCardsWidth <= maxWidth) {
                preferredCardWidth
            } else {
                ((maxWidth - 32.dp - gap * fullCards) / fullCards).coerceAtLeast(1.dp)
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(visibleItems, key = { it.playlist!!.stableKey }) { item ->
                    val playlist = item.playlist!!
                    val title = item.displayTitle
                    PersonalPlaylistCard(
                        title = title,
                        description = item.description ?: "Собрано для вас",
                        coverUri = playlist.cover?.uri,
                        onClick = { onSelect(item) },
                        modifier = Modifier.width(cardWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalPlaylistCard(
    title: String,
    description: String,
    coverUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverBitmap = rememberCoverBitmap(coverUri, size = 200)

    Card(
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Обложка подстраивается под ширину карточки.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(1.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// 📂 5. МОИ ПАПКИ И ЛОКАЛЬНЫЕ ПЛЕЙЛИСТЫ (FlowRow с автопереносом на новую строку)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalLibrarySection(
    localPlaylists: List<LocalPlaylist>,
    customSources: List<CustomMediaSource>,
    onOpenLocalPlaylist: (LocalPlaylist) -> Unit,
    onOpenFolder: (path: String, name: String, src: CustomMediaSource?) -> Unit,
    onAddFolder: () -> Unit,
    onOpenMediaLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Мои папки и плейлисты",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlatformPickerSupported) {
                OutlinedButton(
                    onClick = onAddFolder,
                    modifier = Modifier.heightIn(min = 44.dp).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить папку", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onOpenMediaLibrary,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 44.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Все", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gap = 10.dp
            val columns = if (maxWidth < 540.dp) 2 else ((maxWidth + gap) / 170.dp).toInt().coerceAtLeast(2)
            // Округляем вниз до пикселя, чтобы последний столбец не переносился из-за округления.
            val cardWidth = with(LocalDensity.current) {
                ((constraints.maxWidth - gap.roundToPx() * (columns - 1)) / columns)
                    .coerceAtLeast(1).toDp()
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = columns,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                customSources.forEach { src ->
                    key(src.id) {
                        FolderCard(
                            title = src.name,
                            subtitle = src.path.substringAfterLast('/').ifBlank { "Папка" },
                            onClick = { onOpenFolder(src.path, src.name, src) },
                            modifier = Modifier.width(cardWidth)
                        )
                    }
                }
                localPlaylists.forEach { playlist ->
                    key(playlist.id) {
                        LocalPlaylistCard(
                            playlist = playlist,
                            onClick = { onOpenLocalPlaylist(playlist) },
                            modifier = Modifier.width(cardWidth)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .heightIn(min = 88.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3B82F6).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocalPlaylistCard(
    playlist: LocalPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .heightIn(min = 88.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = Color(0xFF8B5CF6),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.trackPaths.size} треков",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
