package io.github.audiz.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.audiz.YandexCookieService
import kotlin.math.roundToInt

/**
 * 📦 Данные состояния настроек
 */
data class SettingsState(
    val isRecordToDiskActive: Boolean = false,
    val selectedQuality: String = "2",
    val appTheme: String = "Dark",
    val musicStoragePath: String = "",
    val currentAccessToken: String = "",
    val authStatusMessage: String? = null,
    val isFetchingYnison: Boolean = false,
    val ynisonWaveSessionId: String? = null,
    val storageStatusMessage: String? = null,
    val uiMode: String = "auto",
    val crossfadeSeconds: Int = 3,
)

/**
 * ⚡ Действия и колбэки панели настроек
 */
data class SettingsActions(
    val onClose: () -> Unit = {},
    val onToggleRecordToDisk: (Boolean) -> Unit = {},
    val onSaveQuality: (String) -> Unit = {},
    val onSaveTheme: (String) -> Unit = {},
    val onSaveMusicPath: (String) -> Unit = {},
    val onSaveToken: (token: String, onComplete: (Boolean) -> Unit) -> Unit = { _, _ -> },
    val onClearAuthStatus: () -> Unit = {},
    val onFetchYnisonSession: () -> Unit = {},
    val onClearPlaylistsCache: () -> Unit = {},
    val onClearAllDownloadedMusic: () -> Unit = {},
    val onOpenEqualizer: ((initialTab: Int) -> Unit)? = null,
    val onOpenLogs: (() -> Unit)? = null,
    val onSaveUiMode: (String) -> Unit = {},
    val onSaveCrossfade: (Int) -> Unit = {},
)

/**
 * ⚙️ Модальная панель (шторка) настроек приложения
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    visible: Boolean,
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    // 🔙 Системная кнопка "Назад" на Android закрывает настройки
    BackHandler(enabled = visible) {
        focusManager.clearFocus()
        keyboardController?.hide()
        actions.onClose()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        val settingsScrollState = rememberScrollState()
        var dragOffsetY by remember { mutableStateOf(0f) }
        val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY)

        LaunchedEffect(visible) {
            if (!visible) {
                dragOffsetY = 0f
            }
        }

        // 🔲 Полупрозрачный фон (scrim), клик по которому закрывает настройки
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    actions.onClose()
                }
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 76.dp)
                    .offset { IntOffset(0, animatedOffsetY.toInt()) }
                    .animateEnterExit(
                        enter = slideInVertically { it / 2 },
                        exit = slideOutVertically { it / 2 }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Предотвращаем закрытие при клике по самой карточке */ },
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 🔝 Фиксированная шапка с Drag Handle, кнопкой закрытия и жестом свайпа вниз
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        if (dragAmount > 0 || dragOffsetY > 0) {
                                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                            change.consume()
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragOffsetY > 100f) {
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            actions.onClose()
                                        }
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        dragOffsetY = 0f
                                    }
                                )
                            }
                    ) {
                        // Полоска-индикатор (Drag Handle)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Настройки",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TopBarIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "Закрыть настройки",
                                size = 32.dp,
                                outlined = true,
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    actions.onClose()
                                }
                            )
                        }
                    }
                    HorizontalDivider()

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(settingsScrollState)
                                .padding(16.dp)
                                .padding(end = 12.dp)
                        ) {
                            // 💾 Сохранение на диск при воспроизведении
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "Сохранение треков на диск",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        text = "Автоматически сохранять воспроизводимые треки в постоянное хранилище",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.isRecordToDiskActive,
                                    onCheckedChange = { actions.onToggleRecordToDisk(it) },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🎛️ Эквалайзер и срезы частот
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Эквалайзер и срезы частот",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "10 полос, фильтры Low-Cut (HPF) и High-Cut (LPF)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { actions.onOpenEqualizer?.invoke(0) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Эквалайзер",
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { actions.onOpenEqualizer?.invoke(1) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Срезы",
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 📻 Плавный переход (Кроссфейд)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Плавный переход между треками",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (state.crossfadeSeconds == 0) "Выкл (стык)" else "${state.crossfadeSeconds} сек",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.crossfadeSeconds == 0) {
                                    "Умный стык: отрезает тишину в конце трека и переключает мгновенно."
                                } else {
                                    "Радио-сведение: плавное наложение следующего трека за ${state.crossfadeSeconds} сек до конца."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = state.crossfadeSeconds.toFloat(),
                                onValueChange = { actions.onSaveCrossfade(it.roundToInt()) },
                                valueRange = 0f..12f,
                                steps = 11,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🎚️ Выбор качества скачивания (ПЕРВЫМ)
                            Text(
                                text = "Качество скачивания",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.selectedQuality == "1",
                                        onClick = { actions.onSaveQuality("1") }
                                    )
                                    Text("Low", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.selectedQuality == "2",
                                        onClick = { actions.onSaveQuality("2") }
                                    )
                                    Text("High", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🎨 Выбор темы
                            Text(
                                text = "Тема оформления",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.appTheme == "Light",
                                        onClick = { actions.onSaveTheme("Light") }
                                    )
                                    Text("Светлая", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.appTheme == "Dark",
                                        onClick = { actions.onSaveTheme("Dark") }
                                    )
                                    Text("Тёмная", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 📱 Режим интерфейса (Мобильный / ПК / Авто)
                            Text(
                                text = "Вид интерфейса",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.uiMode == "auto",
                                        onClick = { actions.onSaveUiMode("auto") }
                                    )
                                    Text("Авто", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.uiMode == "mobile",
                                        onClick = { actions.onSaveUiMode("mobile") }
                                    )
                                    Text("Мобильный", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = state.uiMode == "desktop",
                                        onClick = { actions.onSaveUiMode("desktop") }
                                    )
                                    Text("ПК", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 📂 Путь сохранения музыки
                            Text(
                                text = "Папка загрузки",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            var pathInput by remember(state.musicStoragePath) {
                                mutableStateOf(state.musicStoragePath)
                            }
                            var pathSaved by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = pathInput,
                                onValueChange = { pathInput = it; pathSaved = false },
                                label = { Text("Путь") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        actions.onSaveMusicPath(pathInput)
                                        pathSaved = true
                                    },
                                    enabled = pathInput.isNotBlank() && pathInput != state.musicStoragePath
                                ) {
                                    Text("💾 Сохранить путь")
                                }
                                if (pathSaved) {
                                    Text(
                                        text = "✅ Сохранено!",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🔑 Авторизация в Яндекс Музыке (OAuth Token)
                            Text(
                                text = "Авторизация в Яндекс Музыке (OAuth)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Нажмите кнопку, чтобы открыть страницу авторизации в Яндекс в браузере.\n2. После подтверждения доступа скопируйте ссылку из адресной строки (или access_token) и вставьте ниже:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            var tokenInput by remember(state.currentAccessToken) {
                                mutableStateOf(state.currentAccessToken)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { uriHandler.openUri(YandexCookieService.OAUTH_AUTH_URL) },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text("🌐 1. Получить токен в браузере")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = {
                                    tokenInput = it
                                    actions.onClearAuthStatus()
                                },
                                label = { Text("OAuth access_token или ссылка из адресной строки") },
                                placeholder = { Text("https://music.yandex.ru/#access_token=AQAAAA... или AQAAAA...") },
                                singleLine = false,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        actions.onSaveToken(tokenInput) { success ->
                                            if (success) {
                                                tokenInput = state.currentAccessToken
                                            }
                                        }
                                    },
                                    enabled = tokenInput.isNotBlank(),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text("💾 2. Сохранить токен")
                                }

                                if (state.authStatusMessage != null) {
                                    Text(
                                        text = state.authStatusMessage,
                                        color = if (state.authStatusMessage.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🌊 Блок Ynison WebSocket (Моя Волна)
                            Text(
                                text = "🌊 Ynison WebSocket (Моя Волна)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { actions.onFetchYnisonSession() },
                                    enabled = !state.isFetchingYnison && state.currentAccessToken.isNotBlank(),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text(if (state.isFetchingYnison) "⏳ Получение..." else "🔄 Запросить Ynison Session")
                                }

                                if (state.ynisonWaveSessionId != null) {
                                    SelectionContainer(modifier = Modifier.align(Alignment.CenterVertically)) {
                                        Text(
                                            text = "ID: ${state.ynisonWaveSessionId}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // 🗑️ Управление хранилищем и кешем
                            Text(
                                text = "Управление хранилищем и кешем",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            var showDeleteAllConfirmation by remember { mutableStateOf(false) }

                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { actions.onClearPlaylistsCache() },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Очистить кеш плейлистов")
                                }

                                OutlinedButton(
                                    onClick = { showDeleteAllConfirmation = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Удалить все скачанные треки")
                                }
                            }

                            if (state.storageStatusMessage != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = state.storageStatusMessage,
                                    color = if (state.storageStatusMessage.startsWith("✅")) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // 💬 Сообщество и отзывы (Telegram)
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Сообщество и отзывы",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Чат пользователей: обсуждение, идеи, предложения и помощь",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                var copiedLink by remember { mutableStateOf(false) }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            uriHandler.openUri("https://t.me/+5PT1eAkb7CxjYzNi")
                                        },
                                        modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Hand),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF2AABEE),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Перейти в Telegram-чат",
                                            maxLines = 1,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString("https://t.me/+5PT1eAkb7CxjYzNi"))
                                            copiedLink = true
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                    ) {
                                        Icon(
                                            imageVector = if (copiedLink) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                            contentDescription = "Скопировать ссылку",
                                            tint = if (copiedLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            // 📋 Журнал логов (Отладка)
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Отладка и логи",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Просмотр и копирование журнала работы приложения",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = { actions.onOpenLogs?.invoke() },
                                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Открыть журнал логов", maxLines = 1)
                                }
                            }

                            if (showDeleteAllConfirmation) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteAllConfirmation = false },
                                    title = { Text("Удаление всей музыки") },
                                    text = {
                                        Text("Вы действительно хотите удалить все скачанные аудиофайлы из папки хранения музыки? Это действие нельзя отменить.")
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showDeleteAllConfirmation = false
                                                actions.onClearAllDownloadedMusic()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            )
                                        ) {
                                            Text("Удалить всё")
                                        }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { showDeleteAllConfirmation = false }) {
                                            Text("Отмена")
                                        }
                                    }
                                )
                            }
                        }

                        PlatformScrollbar(
                            state = settingsScrollState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        } // closes scrim Box
    }
}
