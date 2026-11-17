package io.github.audiz.support

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

@Composable
internal fun StarSupportPrompt(
    manager: StarPromptManager,
    canShow: Boolean,
    isPlaying: Boolean,
    trackId: String?,
    isLiked: Boolean,
    isSaved: Boolean
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState == Lifecycle.State.RESUMED
    val state by manager.state.collectAsState()
    var foregroundMillis by remember { mutableStateOf(0L) }
    var delayReady by remember { mutableStateOf(false) }
    var successfulAction by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var previousTrack by remember { mutableStateOf(trackId) }
    var previousLiked by remember { mutableStateOf(isLiked) }
    var previousSaved by remember { mutableStateOf(isSaved) }
    var openFailed by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        manager.recordActiveDay()
        if (!delayReady) {
            val start = TimeSource.Monotonic.markNow()
            try {
                delay((120_000L - foregroundMillis).coerceAtLeast(0L))
                delayReady = true
                manager.recordActiveDay()
            } finally {
                foregroundMillis += start.elapsedNow().inWholeMilliseconds
            }
        }
    }

    LaunchedEffect(foreground, isPlaying, trackId, isLiked, isSaved) {
        val sameTrack = trackId != null && trackId == previousTrack
        val positiveAction = isPlaying || (sameTrack && ((!previousLiked && isLiked) || (!previousSaved && isSaved)))
        previousTrack = trackId
        previousLiked = isLiked
        previousSaved = isSaved
        if (foreground && positiveAction) {
            successfulAction = true
            manager.recordActiveDay()
        }
    }

    LaunchedEffect(foreground, canShow, delayReady, successfulAction, state?.activeDaysCount) {
        if (foreground && canShow && delayReady && successfulAction && !visible && state?.eligible == true && manager.claimShow()) {
            visible = true
        }
    }

    if (!visible || !foreground || !canShow || state?.completed == true) return

    fun dismiss(permanently: Boolean) {
        if (completing) return
        completing = true
        scope.launch {
            if (permanently) manager.complete() else manager.postpone()
            visible = false
            completing = false
        }
    }

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismiss(permanently = false)
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
        enableDismissFromStartToEnd = !completing,
        enableDismissFromEndToStart = !completing
    ) {
        StarSupportCard(
            openFailed = openFailed,
            enabled = !completing,
            onOpen = {
                try {
                    uriHandler.openUri(PROJECT_GITHUB_URL)
                    dismiss(permanently = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    openFailed = true
                }
            },
            onLater = { dismiss(permanently = false) },
            onNever = { dismiss(permanently = true) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StarSupportCard(
    openFailed: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val compact = maxWidth < 540.dp
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Нравится YamSync? 🎧",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onLater, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Default.Close, contentDescription = "Напомнить позже")
                    }
                }
                Text(
                    "Поддержите проект звездой на GitHub — это поможет другим узнать о YamSync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (openFailed) {
                    Text("Не удалось открыть браузер. Попробуйте ещё раз.", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpen, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text("⭐ Открыть GitHub", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onLater, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text("Напомнить позже", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onNever, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text("Больше не показывать", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
