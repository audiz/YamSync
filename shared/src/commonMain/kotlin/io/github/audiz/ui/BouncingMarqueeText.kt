package io.github.audiz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Бегущая строка, бегающая вперед и назад (bouncing / reverse),
 * если текст не помещается в доступную ширину контейнера.
 * Текст сразу отображается полностью. Движение равномерное (LinearEasing).
 * Скорость увеличена на 10% (~650 мс на символ).
 *
 * Поддерживает:
 * - Двойной клик: выделить весь текст и скопировать в буфер обмена.
 * - Одиночный клик: снять выделение.
 * - Защита от колебаний ширины часов (стабилизация maxScroll, чтобы бегущая строка не сбивалась).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BouncingMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    color: Color = Color.Unspecified,
    pauseStartMs: Long = 1500L,
    pauseEndMs: Long = 1500L,
    msPerChar: Long = 650L // Увеличено на 10% относительно 720L (~650 мс на символ)
) {
    var contentWidthPx by remember(text) { mutableStateOf(0) }
    var containerWidthPx by remember { mutableStateOf(0) }
    val clipboardManager = LocalClipboardManager.current
    var isSelected by remember(text) { mutableStateOf(false) }

    // Стабилизируем maxScroll: небольшие колебания на 1-4 px из-за тикающих часов не должны перезапускать LaunchedEffect
    val rawMaxScroll = (contentWidthPx - containerWidthPx).coerceAtLeast(0)
    var stableMaxScroll by remember(text) { mutableStateOf(rawMaxScroll) }

    if (abs(rawMaxScroll - stableMaxScroll) > 4 || (stableMaxScroll == 0 && rawMaxScroll > 0) || (rawMaxScroll == 0 && stableMaxScroll > 0)) {
        stableMaxScroll = rawMaxScroll
    }

    val scrollOffset = remember(text) { Animatable(0f) }

    // Средняя ширина символа для расчета времени движения
    val approxCharWidthPx = if (text.isNotEmpty() && contentWidthPx > 0) {
        contentWidthPx.toFloat() / text.length.toFloat()
    } else 15f

    LaunchedEffect(stableMaxScroll, text, approxCharWidthPx) {
        if (stableMaxScroll > 0) {
            scrollOffset.snapTo(0f)
            while (isActive) {
                delay(pauseStartMs)
                val hiddenChars = (stableMaxScroll / approxCharWidthPx).coerceAtLeast(1f)
                val duration = (hiddenChars * msPerChar).toInt().coerceAtLeast(3500)

                scrollOffset.animateTo(
                    targetValue = stableMaxScroll.toFloat(),
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing)
                )
                delay(pauseEndMs)
                scrollOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing)
                )
            }
        } else {
            scrollOffset.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isSelected) {
                        isSelected = false
                    }
                },
                onDoubleClick = {
                    isSelected = true
                    clipboardManager.setText(AnnotatedString(text))
                }
            )
    ) {
        if (isSelected) {
            SelectionContainer {
                Text(
                    text = text,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .wrapContentWidth(align = Alignment.Start, unbounded = true)
                        .offset { IntOffset(-scrollOffset.value.roundToInt(), 0) }
                )
            }
        } else {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { textLayoutResult ->
                    contentWidthPx = textLayoutResult.size.width
                },
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .offset { IntOffset(-scrollOffset.value.roundToInt(), 0) }
            )
        }
    }
}

