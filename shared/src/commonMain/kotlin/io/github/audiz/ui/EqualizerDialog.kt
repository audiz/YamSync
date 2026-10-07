package io.github.audiz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.audiz.dsp.AudioVisualizer
import io.github.audiz.dsp.EqualizerEngine
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerDialog(
    initialTab: Int = 0,
    onDismissRequest: () -> Unit
) {
    val eqState by EqualizerEngine.state.collectAsState()
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    var presetsMenuExpanded by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf(AudioVisualizer.latencyCompensationMs.toFloat()) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ==================== 1. ЗАГОЛОВОК И ВКЛЮЧАТЕЛЬ (STICKY) ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (eqState.isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Default.Tune else Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (eqState.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "Эквалайзер",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (eqState.isEnabled) "Включен" else "Выключен (Bypass)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (eqState.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Switch(
                            checked = eqState.isEnabled,
                            onCheckedChange = { EqualizerEngine.setEnabled(it) },
                            thumbContent = if (eqState.isEnabled) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            } else null
                        )

                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // ==================== 2. ВКЛАДКИ (STICKY) ====================
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "10 полос",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            val hpfActive = eqState.hpfHz > 21f
                            val lpfActive = eqState.lpfHz < 19900f
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Срезы частот",
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                                if (hpfActive || lpfActive) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(6.dp)
                                    ) {}
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                // ==================== 3. СКРОЛЛИРУЕМАЯ ОБЛАСТЬ ====================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedTab == 0) {
                        // ===== ВКЛАДКА 0: 10 ПОЛОС ЭКВАЛАЙЗЕРА =====

                        // Пресеты
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (eqState.isEnabled) 1f else 0.4f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { if (eqState.isEnabled) presetsMenuExpanded = true },
                                    enabled = eqState.isEnabled,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = eqState.presetName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }

                                DropdownMenu(
                                    expanded = presetsMenuExpanded,
                                    onDismissRequest = { presetsMenuExpanded = false }
                                ) {
                                    EqualizerEngine.PRESETS.forEach { preset ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = preset.name,
                                                    fontWeight = if (preset.name == eqState.presetName) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (preset.name == eqState.presetName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                EqualizerEngine.applyPreset(preset)
                                                presetsMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            TextButton(
                                onClick = { EqualizerEngine.resetToFlat() },
                                enabled = eqState.isEnabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Сброс", maxLines = 1)
                            }
                        }

                        // 10 Полос (Горизонтальный скролл при необходимости)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (eqState.isEnabled) 1f else 0.4f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "+12 dB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "0 dB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "-12 dB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val freqLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                                    for (i in 0..9) {
                                        VerticalBandSlider(
                                            gainDb = eqState.gains[i],
                                            freqLabel = freqLabels[i],
                                            enabled = eqState.isEnabled,
                                            onGainChange = { newGain ->
                                                EqualizerEngine.setBandGain(i, newGain)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Быстрая панель перехода к срезам
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedTab = 1 }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    val hpfLabel = if (eqState.hpfHz <= 21f) "Выкл" else "${eqState.hpfHz.roundToInt()} Гц"
                                    val lpfLabel = if (eqState.lpfHz >= 19900f) "Выкл" else "${(eqState.lpfHz / 1000f * 10).roundToInt() / 10f} кГц"
                                    Column {
                                        Text(
                                            text = "Срезы: $hpfLabel / $lpfLabel",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "HPF и LPF фильтры 12 дБ/окт",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = { selectedTab = 1 },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Срезы →", fontSize = 11.sp)
                                }
                            }
                        }

                    } else {
                        // ===== ВКЛАДКА 1: СРЕЗЫ ЧАСТОТ (HPF И LPF) =====

                        // Карточка диапазона пропускания
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth().alpha(if (eqState.isEnabled) 1f else 0.4f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val hpfText = if (eqState.hpfHz <= 21f) "20 Гц" else "${eqState.hpfHz.roundToInt()} Гц"
                                val lpfText = if (eqState.lpfHz >= 19900f) "20 кГц" else "${(eqState.lpfHz / 1000f * 10).roundToInt() / 10f} кГц"
                                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                    Text(
                                        text = "Диапазон частот:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$hpfText  ➔  $lpfText",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                TextButton(
                                    onClick = { EqualizerEngine.resetCutoffs() },
                                    enabled = eqState.isEnabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Сброс", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        // 1. Срез НЧ (Low-Cut / HPF)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth().alpha(if (eqState.isEnabled) 1f else 0.4f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                        Text(
                                            text = "Low-Cut (High-Pass / Срез НЧ)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Отсекает саб-гул ниже выбранной частоты",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (eqState.hpfHz > 21f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (eqState.hpfHz <= 21f) "Выкл" else "${eqState.hpfHz.roundToInt()} Гц",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (eqState.hpfHz > 21f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Slider(
                                    value = eqState.hpfHz,
                                    onValueChange = { EqualizerEngine.setHpfCutoff(it) },
                                    valueRange = 20f..250f,
                                    enabled = eqState.isEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Чипы с автоматическим переносом (FlowRow)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val hpfPresets = listOf(
                                        20f to "Выкл",
                                        30f to "30 Гц",
                                        50f to "50 Гц",
                                        80f to "80 Гц",
                                        120f to "120 Гц"
                                    )
                                    hpfPresets.forEach { (freq, label) ->
                                        val isSelected = kotlin.math.abs(eqState.hpfHz - freq) < 2f
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { EqualizerEngine.setHpfCutoff(freq) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            enabled = eqState.isEnabled,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Срез ВЧ (High-Cut / LPF)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth().alpha(if (eqState.isEnabled) 1f else 0.4f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                        Text(
                                            text = "High-Cut (Low-Pass / Срез ВЧ)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Срезает избыточную резкость выше частоты",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (eqState.lpfHz < 19900f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (eqState.lpfHz >= 19900f) "Выкл" else "${(eqState.lpfHz / 1000f * 10).roundToInt() / 10f} кГц",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (eqState.lpfHz < 19900f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Slider(
                                    value = eqState.lpfHz,
                                    onValueChange = { EqualizerEngine.setLpfCutoff(it) },
                                    valueRange = 4000f..20000f,
                                    enabled = eqState.isEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Чипы с автоматическим переносом (FlowRow)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val lpfPresets = listOf(
                                        20000f to "Выкл",
                                        18000f to "18 кГц",
                                        16000f to "16 кГц",
                                        12000f to "12 кГц",
                                        8000f to "8 кГц"
                                    )
                                    lpfPresets.forEach { (freq, label) ->
                                        val isSelected = kotlin.math.abs(eqState.lpfHz - freq) < 200f
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { EqualizerEngine.setLpfCutoff(freq) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            enabled = eqState.isEnabled,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Синхронизация спектра (Audio-Visual Sync)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                        Text(
                                            text = "Синхронизация спектра (Задержка)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Устраняет опережение эквалайзера перед звуком",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (latencyMs.roundToInt() > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (latencyMs.roundToInt() <= 0) "Выкл (0 мс)" else "${latencyMs.roundToInt()} мс",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (latencyMs.roundToInt() > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Slider(
                                    value = latencyMs,
                                    onValueChange = {
                                        val snapped = (it / 10f).roundToInt() * 10f
                                        latencyMs = snapped
                                        AudioVisualizer.latencyCompensationMs = snapped.toLong()
                                    },
                                    valueRange = 0f..350f,
                                    steps = 34,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val presets = listOf(
                                        0f to "0 мс (Выкл)",
                                        80f to "80 мс",
                                        140f to "140 мс (По умолч.)",
                                        200f to "200 мс (BT)",
                                        280f to "280 мс"
                                    )
                                    presets.forEach { (presetMs, label) ->
                                        val isSelected = kotlin.math.abs(latencyMs - presetMs) < 5f
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                latencyMs = presetMs
                                                AudioVisualizer.latencyCompensationMs = presetMs.toLong()
                                            },
                                            label = { Text(label, fontSize = 11.sp) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Кнопка возврата к 10 полосам
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            OutlinedButton(
                                onClick = { selectedTab = 0 },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("← Назад к 10 полосам", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ==================== 4. КНОПКА ЗАКРЫТИЯ (STICKY FOOTER) ====================
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismissRequest,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

/**
 * 🎚 Вертикальный слайдер одной полосы эквалайзера (компактный для любых экранов).
 */
@Composable
private fun VerticalBandSlider(
    gainDb: Float,
    freqLabel: String,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackHeight = 120.dp
    val trackWidth = 6.dp
    val thumbSize = 16.dp

    Column(
        modifier = modifier.width(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Значение dB
        val gainText = when {
            gainDb > 0.05f -> "+${(gainDb * 10).roundToInt() / 10f}"
            gainDb < -0.05f -> "${(gainDb * 10).roundToInt() / 10f}"
            else -> "0"
        }
        Text(
            text = gainText,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = if (kotlin.math.abs(gainDb) > 0.5f) FontWeight.Bold else FontWeight.Normal,
            color = if (kotlin.math.abs(gainDb) > 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        // Вертикальный трек со слайдером
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(28.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                        val newGain = (fraction * 24f - 12f).coerceIn(-12f, 12f)
                        onGainChange((newGain * 2).roundToInt() / 2f)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                        val newGain = (fraction * 24f - 12f).coerceIn(-12f, 12f)
                        onGainChange((newGain * 2).roundToInt() / 2f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Фон линии
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(trackWidth)
                    .clip(RoundedCornerShape(trackWidth / 2))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // Центральная линия (0 dB)
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // Активная заливка от центра к ползунку
            val normalizedGain = ((gainDb + 12f) / 24f).coerceIn(0f, 1f)
            val fillHeightFraction = kotlin.math.abs(gainDb) / 24f
            val isPositive = gainDb >= 0f

            Box(
                modifier = Modifier
                    .width(trackWidth)
                    .fillMaxHeight()
            ) {
                if (fillHeightFraction > 0.005f) {
                    Box(
                        modifier = Modifier
                            .align(if (isPositive) Alignment.Center else Alignment.Center)
                            .offset(y = if (isPositive) (-trackHeight / 4) * (gainDb / 12f) else (trackHeight / 4) * (-gainDb / 12f))
                            .fillMaxWidth()
                            .fillMaxHeight(fillHeightFraction)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    )
                }
            }

            // Бегунок (Thumb)
            val thumbOffsetY = ((1f - normalizedGain) * (trackHeight.value - thumbSize.value)).dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetY)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (kotlin.math.abs(gainDb) > 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }

        // Подпись частоты
        Text(
            text = freqLabel,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
