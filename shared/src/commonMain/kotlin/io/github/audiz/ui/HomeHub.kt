package io.github.audiz.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.audiz.SearchViewModel
import io.github.audiz.isPlatformPickerSupported
import io.github.audiz.models.CustomMediaSource
import io.github.audiz.models.LastPlaybackSession
import io.github.audiz.models.LocalPlaylist
import io.github.audiz.models.PersonalPlaylistItemData
import io.github.audiz.models.ThematicWavePreset
import io.github.audiz.pickDirectory

/**
 * 🏠 Главный экран-хаб приложения (Home Hub).
 * Организован с автоматическим переносом элементов на следующую строку (FlowRow),
 * чтобы на мобильных устройствах ни один элемент не скрывался за правый край экрана:
 * 1. Герой-баннер «Моя Волна» (чипы стилей переносятся на новую строку).
 * 2. Плитки быстрого доступа (перенос 2x2 на смартфонах).
 * 3. Карточка быстрого продолжения последней сессии (если есть).
 * 4. Персональные подборки Яндекса (карточки переносятся на новую строку).
 * 5. Локальные плейлисты и папки (карточки переносятся на новую строку).
 */
@Composable
fun HomeHub(
    viewModel: SearchViewModel,
    onOpenMediaLibrary: () -> Unit,
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
                    onSelect = { uuid, title -> viewModel.loadPlaylistByUuid(uuid, title) },
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
                        viewModel.openFolderPlaylist(path, name, src, isRecursive = true)
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
    val isWaveActive = viewModel.isWaveMode
    val isPlayingWave = isWaveActive && viewModel.playerIsPlaying && !viewModel.playerIsPaused
    val currentWaveTitle = viewModel.currentWaveTitle?.takeIf {
        it.isNotBlank() && !it.equals("Моя Волна", ignoreCase = true)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (isWaveActive) primaryColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isWaveActive) 3.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = if (isWaveActive) 0.16f else 0.08f),
                            tertiaryColor.copy(alpha = if (isWaveActive) 0.12f else 0.04f),
                            surfaceColor.copy(alpha = 0.15f)
                        )
                    )
                )
                .padding(12.dp)
        ) {
            // Верхняя часть: Иконка, Заголовок, Кнопка Play
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Иконка Волны с мягким фоном
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isWaveActive) primaryColor else primaryColor.copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Waves,
                        contentDescription = null,
                        tint = if (isWaveActive) MaterialTheme.colorScheme.onPrimary else primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Текстовая информация
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (currentWaveTitle != null) "Моя Волна • $currentWaveTitle" else "Моя Волна",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val statusText = when {
                        viewModel.isWaveLoading -> "Загрузка рекомендаций..."
                        isPlayingWave && viewModel.playerTrackTitle.isNotBlank() ->
                            "${viewModel.playerTrackTitle} — ${viewModel.playerArtistName}"
                        isWaveActive -> "Нажмите Play для продолжения"
                        else -> "Бесконечный поток под ваш вкус"
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Кнопка запуска / паузы Волны
                FilledIconButton(
                    onClick = { viewModel.togglePlayPauseWave() },
                    modifier = Modifier.size(38.dp).pointerHoverIcon(PointerIcon.Hand),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = primaryColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (viewModel.isWaveLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlayingWave) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlayingWave) "Пауза" else "Слушать Волну",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Чипы настроений и стилей с автопереносом на следующую строку (FlowRow)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Основной чип «★ Персональная»
                val isDefaultActive = isWaveActive && (viewModel.currentWaveSeeds.isEmpty() || currentWaveTitle == null)
                FilterChip(
                    selected = isDefaultActive,
                    onClick = { viewModel.resetToDefaultWave() },
                    label = {
                        Text(
                            "★ Персональная",
                            fontSize = 12.sp,
                            fontWeight = if (isDefaultActive) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isDefaultActive) {
                        { Icon(Icons.Filled.Waves, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    } else null,
                    modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                )

                // Недавние волны (до 3 штук)
                viewModel.recentThematicWaves.forEach { wave ->
                    val isSelected = isWaveActive && viewModel.currentWaveSeeds == wave.seeds
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.startThematicWave(wave.title, wave.seeds) },
                        label = {
                            Text(
                                wave.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                    )
                }

                // Пресеты стилей
                val defaultList = viewModel.defaultThematicWaves.filterNot { dw ->
                    viewModel.recentThematicWaves.any { it.title == dw.title || it.seeds == dw.seeds }
                }

                defaultList.forEach { wave ->
                    val isSelected = isWaveActive && viewModel.currentWaveSeeds == wave.seeds
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.startThematicWave(wave.title, wave.seeds) },
                        label = {
                            Text(
                                wave.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand)
                    )
                }

                // Кнопка обновления пресетов
                if (viewModel.defaultThematicWaves.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.refreshDefaultThematicWaves() },
                        modifier = Modifier.size(30.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Другие стили",
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// ⚡ 2. СЕКЦИЯ БЫСТРОГО ДОСТУПА (Spotify Style с переносом на след. строку)
// ============================================================================

@Composable
private fun QuickAccessSection(
    viewModel: SearchViewModel,
    onOpenMediaLibrary: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier
) {
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
    modifier: Modifier = Modifier
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
                overflow = TextOverflow.Ellipsis
            )
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
// ✨ 4. ПЕРСОНАЛЬНЫЕ ПОДБОРКИ ЯНДЕКСА (FlowRow с автопереносом на новую строку)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonalPlaylistsSection(
    items: List<PersonalPlaylistItemData>,
    onSelect: (uuid: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
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
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { item ->
                val playlist = item.playlist ?: return@forEach
                val title = playlist.title.ifBlank {
                    when (item.playlistType) {
                        "playlistOfTheDay" -> "Плейлист дня"
                        "recentTracks" -> "Премьера"
                        "neverHeard" -> "Дежавю"
                        else -> "Подборка"
                    }
                }
                val coverUri = playlist.cover?.uri

                PersonalPlaylistCard(
                    title = title,
                    description = item.description ?: "Собрано для вас",
                    coverUri = coverUri,
                    onClick = { onSelect(playlist.playlistUuid, title) }
                )
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
            .width(112.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Квадратная обложка (96x96dp)
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
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(1.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
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
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TextButton(
                onClick = onOpenMediaLibrary,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp).pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Все", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Кнопка добавления новой папки
            if (isPlatformPickerSupported) {
                AddFolderCard(
                    onClick = onAddFolder,
                    modifier = Modifier.width(112.dp)
                )
            }

            // Добавленные папки пользователя
            customSources.forEach { src ->
                FolderCard(
                    title = src.name,
                    subtitle = src.path.substringAfterLast('/').ifBlank { "Папка" },
                    onClick = { onOpenFolder(src.path, src.name, src) },
                    modifier = Modifier.width(112.dp)
                )
            }

            // Локальные плейлисты
            localPlaylists.forEach { pl ->
                LocalPlaylistCard(
                    playlist = pl,
                    onClick = { onOpenLocalPlaylist(pl) },
                    modifier = Modifier.width(112.dp)
                )
            }
        }
    }
}

@Composable
private fun AddFolderCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(88.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Добавить папку",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
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
            .height(88.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
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
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
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
            .height(88.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
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
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.trackPaths.size} треков",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
