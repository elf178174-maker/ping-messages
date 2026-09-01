package com.ping.messenger.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Ping's brand palette.
 *
 * The scheme is a Material 3 tonal palette built around a calm teal primary. Teal reads as
 * "communication" without the saturation of the usual messenger greens, and its complement
 * (a soft slate blue) gives us a second accent for links, mentions and calls without
 * introducing a third hue family.
 *
 * These are hand-tuned tonal values rather than a runtime-generated scheme so that the
 * non-dynamic (pre-Android 12, or dynamic-colour-off) experience is deliberate rather than
 * whatever a seed generator happens to produce.
 */

// ---- Light ----------------------------------------------------------------
internal val LightPrimary = Color(0xFF006B5B)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFF7BF8DF)
internal val LightOnPrimaryContainer = Color(0xFF00201A)
internal val LightSecondary = Color(0xFF4A635C)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFCCE8DF)
internal val LightOnSecondaryContainer = Color(0xFF06201A)
internal val LightTertiary = Color(0xFF416276)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFC4E7FF)
internal val LightOnTertiaryContainer = Color(0xFF001E2C)
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)
internal val LightBackground = Color(0xFFFAFDFA)
internal val LightOnBackground = Color(0xFF191C1B)
internal val LightSurface = Color(0xFFFAFDFA)
internal val LightOnSurface = Color(0xFF191C1B)
internal val LightSurfaceVariant = Color(0xFFDBE5E0)
internal val LightOnSurfaceVariant = Color(0xFF3F4945)
internal val LightOutline = Color(0xFF6F7975)
internal val LightOutlineVariant = Color(0xFFBFC9C4)
internal val LightInverseSurface = Color(0xFF2E3130)
internal val LightInverseOnSurface = Color(0xFFEFF1EF)
internal val LightInversePrimary = Color(0xFF5CDBC3)
internal val LightSurfaceDim = Color(0xFFD9DDDA)
internal val LightSurfaceBright = Color(0xFFFAFDFA)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF4F7F4)
internal val LightSurfaceContainer = Color(0xFFEEF2EF)
internal val LightSurfaceContainerHigh = Color(0xFFE9EDEA)
internal val LightSurfaceContainerHighest = Color(0xFFE3E7E4)
internal val LightScrim = Color(0xFF000000)

// ---- Dark -----------------------------------------------------------------
internal val DarkPrimary = Color(0xFF5CDBC3)
internal val DarkOnPrimary = Color(0xFF00382F)
internal val DarkPrimaryContainer = Color(0xFF005045)
internal val DarkOnPrimaryContainer = Color(0xFF7BF8DF)
internal val DarkSecondary = Color(0xFFB1CCC3)
internal val DarkOnSecondary = Color(0xFF1D352F)
internal val DarkSecondaryContainer = Color(0xFF334B45)
internal val DarkOnSecondaryContainer = Color(0xFFCCE8DF)
internal val DarkTertiary = Color(0xFFA8CBE2)
internal val DarkOnTertiary = Color(0xFF0C3446)
internal val DarkTertiaryContainer = Color(0xFF294B5D)
internal val DarkOnTertiaryContainer = Color(0xFFC4E7FF)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)
internal val DarkBackground = Color(0xFF101413)
internal val DarkOnBackground = Color(0xFFE0E3E1)
internal val DarkSurface = Color(0xFF101413)
internal val DarkOnSurface = Color(0xFFE0E3E1)
internal val DarkSurfaceVariant = Color(0xFF3F4945)
internal val DarkOnSurfaceVariant = Color(0xFFBFC9C4)
internal val DarkOutline = Color(0xFF899390)
internal val DarkOutlineVariant = Color(0xFF3F4945)
internal val DarkInverseSurface = Color(0xFFE0E3E1)
internal val DarkInverseOnSurface = Color(0xFF2E3130)
internal val DarkInversePrimary = Color(0xFF006B5B)
internal val DarkSurfaceDim = Color(0xFF101413)
internal val DarkSurfaceBright = Color(0xFF353A38)
internal val DarkSurfaceContainerLowest = Color(0xFF0B0F0E)
internal val DarkSurfaceContainerLow = Color(0xFF191C1B)
internal val DarkSurfaceContainer = Color(0xFF1D2120)
internal val DarkSurfaceContainerHigh = Color(0xFF272B29)
internal val DarkSurfaceContainerHighest = Color(0xFF323634)
internal val DarkScrim = Color(0xFF000000)

/**
 * Colours that carry semantics Material 3 has no slot for: the two chat bubble sides,
 * presence, and the delivery-state tick.
 *
 * They live in a [androidx.compose.runtime.CompositionLocal] ([LocalPingColors]) so that
 * screens read them the same way they read [androidx.compose.material3.MaterialTheme].
 */
@androidx.compose.runtime.Immutable
data class PingColors(
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val bubbleMeta: Color,
    val outgoingBubbleMeta: Color,
    val online: Color,
    val readTick: Color,
    val unreadBadge: Color,
    val onUnreadBadge: Color,
    val mention: Color,
    val chatBackground: Color,
    val systemBubble: Color,
    val onSystemBubble: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

internal val LightPingColors = PingColors(
    outgoingBubble = Color(0xFFCFF4E8),
    onOutgoingBubble = Color(0xFF08251F),
    incomingBubble = Color(0xFFFFFFFF),
    onIncomingBubble = Color(0xFF191C1B),
    bubbleMeta = Color(0xFF6B7773),
    outgoingBubbleMeta = Color(0xFF3E6B60),
    online = Color(0xFF1EA97C),
    readTick = Color(0xFF2A8CDB),
    unreadBadge = Color(0xFF006B5B),
    onUnreadBadge = Color(0xFFFFFFFF),
    mention = Color(0xFF1B6DA8),
    chatBackground = Color(0xFFF2F5F3),
    systemBubble = Color(0xFFE4EAE7),
    onSystemBubble = Color(0xFF414B47),
    danger = Color(0xFFBA1A1A),
    success = Color(0xFF1EA97C),
    warning = Color(0xFFB2711A),
)

internal val DarkPingColors = PingColors(
    outgoingBubble = Color(0xFF14453C),
    onOutgoingBubble = Color(0xFFDCEFE9),
    incomingBubble = Color(0xFF1F2422),
    onIncomingBubble = Color(0xFFE0E3E1),
    bubbleMeta = Color(0xFF95A19D),
    outgoingBubbleMeta = Color(0xFF8FBDB1),
    online = Color(0xFF4ED2A4),
    readTick = Color(0xFF62B4F0),
    unreadBadge = Color(0xFF5CDBC3),
    onUnreadBadge = Color(0xFF00382F),
    mention = Color(0xFF7FBEEA),
    chatBackground = Color(0xFF0C100F),
    systemBubble = Color(0xFF1D2120),
    onSystemBubble = Color(0xFFAEB8B4),
    danger = Color(0xFFFFB4AB),
    success = Color(0xFF4ED2A4),
    warning = Color(0xFFE0A458),
)
