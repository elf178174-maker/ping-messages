package com.ping.messenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography.
 *
 * Ping deliberately ships no custom font file: the platform default (Roboto, or whatever the
 * user's device has substituted) is what the rest of their phone uses, respects their font
 * settings, and costs nothing to download. What we do tune is the scale — messaging is a
 * reading-heavy activity, so body text sits slightly larger and looser than the Material
 * default, and the tighter tracking of the display styles is dialled back.
 */
private val Default = FontFamily.Default

private val TrimmingLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val PingTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 54.sp,
        lineHeight = 62.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 35.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 31.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp,
        lineHeightStyle = TrimmingLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = TrimmingLineHeight,
    ),
    bodySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.45.sp,
    ),
)

/** The style used for the body of a chat bubble. Slightly looser than [Typography.bodyLarge]. */
val MessageBodyStyle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.1.sp,
    lineHeightStyle = TrimmingLineHeight,
)

/** Timestamps and delivery ticks inside a bubble. */
val MessageMetaStyle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.2.sp,
)

/**
 * A message made up only of emoji is rendered large, the way every other messenger does it.
 * The size steps down as the count goes up so three emoji still fit a narrow screen.
 */
fun emojiOnlyStyle(emojiCount: Int): TextStyle = MessageBodyStyle.copy(
    fontSize = when {
        emojiCount <= 1 -> 52.sp
        emojiCount == 2 -> 44.sp
        else -> 36.sp
    },
    lineHeight = when {
        emojiCount <= 1 -> 60.sp
        emojiCount == 2 -> 52.sp
        else -> 44.sp
    },
)
