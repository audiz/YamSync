package io.github.audiz.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 🎨 Предустановленные варианты акцентного цвета приложения.
 */
enum class AppAccentColor(
    val key: String,
    val title: String,
    val previewColor: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color
) {
    PURPLE(
        key = "purple",
        title = "Фиолетовый",
        previewColor = Color(0xFFD0BCFF),
        primaryDark = Color(0xFFD0BCFF),
        onPrimaryDark = Color(0xFF381E72),
        primaryContainerDark = Color(0xFF4F378B),
        onPrimaryContainerDark = Color(0xFFEADDFF),
        primaryLight = Color(0xFF6750A4),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFEADDFF),
        onPrimaryContainerLight = Color(0xFF21005D)
    ),
    YANDEX(
        key = "yandex",
        title = "Жёлтый",
        previewColor = Color(0xFFFFCC00),
        primaryDark = Color(0xFFFFCC00),
        onPrimaryDark = Color(0xFF241A00),
        primaryContainerDark = Color(0xFF574300),
        onPrimaryContainerDark = Color(0xFFFFE088),
        primaryLight = Color(0xFFE6A100),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFE799),
        onPrimaryContainerLight = Color(0xFF2E2000)
    ),
    EMERALD(
        key = "emerald",
        title = "Изумруд",
        previewColor = Color(0xFF2DD881),
        primaryDark = Color(0xFF2DD881),
        onPrimaryDark = Color(0xFF00381E),
        primaryContainerDark = Color(0xFF00522E),
        onPrimaryContainerDark = Color(0xFF67F8A7),
        primaryLight = Color(0xFF059669),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFA7F3D0),
        onPrimaryContainerLight = Color(0xFF002111)
    ),
    CYAN(
        key = "cyan",
        title = "Лазурный",
        previewColor = Color(0xFF38BDF8),
        primaryDark = Color(0xFF38BDF8),
        onPrimaryDark = Color(0xFF00354A),
        primaryContainerDark = Color(0xFF004D6A),
        onPrimaryContainerDark = Color(0xFFBAE6FD),
        primaryLight = Color(0xFF0284C7),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFE0F2FE),
        onPrimaryContainerLight = Color(0xFF001E2B)
    ),
    CORAL(
        key = "coral",
        title = "Коралл",
        previewColor = Color(0xFFFB7185),
        primaryDark = Color(0xFFFB7185),
        onPrimaryDark = Color(0xFF4C0018),
        primaryContainerDark = Color(0xFF700027),
        onPrimaryContainerDark = Color(0xFFFFD9DF),
        primaryLight = Color(0xFFE11D48),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFE4E6),
        onPrimaryContainerLight = Color(0xFF3B0011)
    ),
    ORANGE(
        key = "orange",
        title = "Оранжевый",
        previewColor = Color(0xFFFB923C),
        primaryDark = Color(0xFFFB923C),
        onPrimaryDark = Color(0xFF4E1D00),
        primaryContainerDark = Color(0xFF712D00),
        onPrimaryContainerDark = Color(0xFFFFDCC5),
        primaryLight = Color(0xFFEA580C),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFEDD5),
        onPrimaryContainerLight = Color(0xFF381000)
    ),
    RUBY(
        key = "ruby",
        title = "Рубин",
        previewColor = Color(0xFFF87171),
        primaryDark = Color(0xFFF87171),
        onPrimaryDark = Color(0xFF450A0A),
        primaryContainerDark = Color(0xFF7F1D1D),
        onPrimaryContainerDark = Color(0xFFFECACA),
        primaryLight = Color(0xFFDC2626),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFEE2E2),
        onPrimaryContainerLight = Color(0xFF450A0A)
    ),
    SILVER(
        key = "silver",
        title = "Серебро",
        previewColor = Color(0xFF94A3B8),
        primaryDark = Color(0xFF94A3B8),
        onPrimaryDark = Color(0xFF0F172A),
        primaryContainerDark = Color(0xFF334155),
        onPrimaryContainerDark = Color(0xFFF1F5F9),
        primaryLight = Color(0xFF475569),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFE2E8F0),
        onPrimaryContainerLight = Color(0xFF0F172A)
    );

    companion object {
        fun fromKey(key: String): AppAccentColor {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PURPLE
        }
    }
}

/**
 * Генерирует адаптированную цветовую схему Material 3 на основе выбранного акцентного цвета.
 */
fun buildAppColorScheme(isDark: Boolean, accent: AppAccentColor): ColorScheme {
    val baseScheme = if (isDark) darkColorScheme() else lightColorScheme()
    return if (isDark) {
        baseScheme.copy(
            primary = accent.primaryDark,
            onPrimary = accent.onPrimaryDark,
            primaryContainer = accent.primaryContainerDark,
            onPrimaryContainer = accent.onPrimaryContainerDark,
            inversePrimary = accent.primaryLight,
        )
    } else {
        baseScheme.copy(
            primary = accent.primaryLight,
            onPrimary = accent.onPrimaryLight,
            primaryContainer = accent.primaryContainerLight,
            onPrimaryContainer = accent.onPrimaryContainerLight,
            inversePrimary = accent.primaryDark,
        )
    }
}
