package com.ping.messenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Chat wallpapers.
 *
 * Deliberately a small set of restrained, theme-aware surfaces rather than photographic
 * backgrounds. A busy wallpaper is the fastest way to make message text unreadable, so each
 * option here is a low-contrast tint or gradient chosen to sit *behind* bubbles without
 * competing with them, and each one is defined separately for light and dark.
 */
enum class ChatWallpaper(val id: String, val label: String) {
    DEFAULT("default", "Default"),
    PAPER("paper", "Paper"),
    MIST("mist", "Mist"),
    SAGE("sage", "Sage"),
    DUSK("dusk", "Dusk"),
    CHARCOAL("charcoal", "Charcoal"),
    ;

    companion object {
        fun fromId(id: String?): ChatWallpaper =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Applies the selected wallpaper as the background of the transcript. */
fun Modifier.wallpaperModifier(wallpaperId: String?): Modifier = composed {
    val wallpaper = ChatWallpaper.fromId(wallpaperId)
    val dark = isDarkTheme()
    when (wallpaper) {
        ChatWallpaper.DEFAULT -> background(PingTheme.colors.chatBackground)
        ChatWallpaper.PAPER -> background(if (dark) Color(0xFF141210) else Color(0xFFF7F3EC))
        ChatWallpaper.MIST -> background(
            Brush.verticalGradient(
                if (dark) {
                    listOf(Color(0xFF0D1214), Color(0xFF121A1D))
                } else {
                    listOf(Color(0xFFF1F5F7), Color(0xFFE8EFF2))
                },
            ),
        )
        ChatWallpaper.SAGE -> background(
            Brush.verticalGradient(
                if (dark) {
                    listOf(Color(0xFF0E1411), Color(0xFF131C17))
                } else {
                    listOf(Color(0xFFF1F6F1), Color(0xFFE7F0E8))
                },
            ),
        )
        ChatWallpaper.DUSK -> background(
            Brush.verticalGradient(
                if (dark) {
                    listOf(Color(0xFF11101A), Color(0xFF181624))
                } else {
                    listOf(Color(0xFFF3F2F8), Color(0xFFEAE8F3))
                },
            ),
        )
        ChatWallpaper.CHARCOAL -> background(if (dark) Color(0xFF0A0A0A) else Color(0xFFEDEDED))
    }
}

@Composable
private fun isDarkTheme(): Boolean =
    androidx.compose.material3.MaterialTheme.colorScheme.background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
