package com.ping.messenger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** How the user wants light/dark resolved. Persisted in [com.ping.messenger.core.datastore.AppPreferences]. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Extra, non-Material colours. See [PingColors]. */
val LocalPingColors: ProvidableCompositionLocal<PingColors> =
    staticCompositionLocalOf { LightPingColors }

/** True when animations should be suppressed for accessibility. */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    scrim = LightScrim,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    scrim = DarkScrim,
)

/**
 * Raises the contrast of a scheme for users who have asked for it. Text-bearing roles move
 * toward pure black/white and outlines get stronger, which is the cheap, predictable version
 * of a full high-contrast palette.
 */
private fun ColorScheme.highContrast(dark: Boolean): ColorScheme {
    val strongOn = if (dark) Color.White else Color.Black
    return copy(
        onBackground = strongOn,
        onSurface = strongOn,
        onSurfaceVariant = if (dark) Color(0xFFE6EBE9) else Color(0xFF232826),
        outline = if (dark) Color(0xFFB9C3BF) else Color(0xFF4A5350),
        outlineVariant = if (dark) Color(0xFF6D7773) else Color(0xFF8E9894),
    )
}

private fun PingColors.highContrast(dark: Boolean): PingColors = copy(
    onIncomingBubble = if (dark) Color.White else Color.Black,
    onOutgoingBubble = if (dark) Color.White else Color.Black,
    bubbleMeta = if (dark) Color(0xFFC4CECA) else Color(0xFF3E4744),
    outgoingBubbleMeta = if (dark) Color(0xFFBFE3D9) else Color(0xFF204840),
)

/**
 * The single theme wrapper every Ping screen sits inside.
 *
 * @param themeMode the user's light/dark preference
 * @param dynamicColor honour the Android 12+ wallpaper palette when available
 * @param highContrast strengthen text and outline contrast
 * @param reduceMotion suppress non-essential animation
 */
@Composable
fun PingTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    highContrast: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val scheme = if (highContrast) baseScheme.highContrast(dark) else baseScheme

    // When the palette comes from the wallpaper, the bubble colours have to follow it or the
    // chat looks like it belongs to a different app than the rest of the UI.
    val baseExtras = if (dark) DarkPingColors else LightPingColors
    val extras = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            baseExtras.derivedFrom(scheme, dark)
        else -> baseExtras
    }.let { if (highContrast) it.highContrast(dark) else it }

    CompositionLocalProvider(
        LocalPingColors provides extras,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PingTypography,
            shapes = PingShapes,
            content = content,
        )
    }
}

private fun PingColors.derivedFrom(scheme: ColorScheme, dark: Boolean): PingColors = copy(
    outgoingBubble = scheme.primaryContainer,
    onOutgoingBubble = scheme.onPrimaryContainer,
    incomingBubble = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
    onIncomingBubble = scheme.onSurface,
    bubbleMeta = scheme.onSurfaceVariant,
    outgoingBubbleMeta = scheme.onPrimaryContainer.copy(alpha = 0.72f),
    unreadBadge = scheme.primary,
    onUnreadBadge = scheme.onPrimary,
    chatBackground = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
    systemBubble = scheme.surfaceContainerHigh,
    onSystemBubble = scheme.onSurfaceVariant,
    mention = scheme.tertiary,
)

/** Shorthand for [LocalPingColors]. */
object PingTheme {
    val colors: PingColors
        @Composable @ReadOnlyComposable get() = LocalPingColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/** True when [this] is light enough that dark content should be drawn on top of it. */
fun Color.prefersDarkContent(): Boolean = luminance() > 0.5f
