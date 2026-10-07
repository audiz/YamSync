package io.github.audiz.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
actual fun DesktopVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier,
    buttonSize: Dp,
    bottomPlayerPadding: Dp,
    hoverDelayMs: Long
) {
    // РќР° iOS СЂРµРіСѓР»СЏС‚РѕСЂ РіСЂРѕРјРєРѕСЃС‚Рё СЃРєСЂС‹С‚ РІ РїР»РµРµСЂРµ
}