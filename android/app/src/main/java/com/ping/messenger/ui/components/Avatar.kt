package com.ping.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.ping.messenger.R
import com.ping.messenger.core.common.TextUtils
import com.ping.messenger.ui.theme.PingTheme
import kotlin.math.absoluteValue

/**
 * The avatar used everywhere a person or group is shown.
 *
 * When there is no photo it falls back to initials on a colour derived from the entity's id.
 * Deriving from the id rather than picking at random means the same person is the same colour
 * on every screen and across launches, which is what makes a chat list scannable.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    photoUrl: String? = null,
    seed: String = name,
    size: Dp = 48.dp,
    isGroup: Boolean = false,
    isOnline: Boolean = false,
    showPresence: Boolean = false,
    contentDescription: String? = null,
) {
    val description = contentDescription
        ?: stringResource(R.string.cd_avatar, name.ifBlank { "?" })

    // The avatar reads as a single element to a screen reader: without this the initials text
    // and the presence dot would each be announced separately.
    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) { this.contentDescription = description },
    ) {
        if (photoUrl.isNullOrBlank()) {
            InitialsAvatar(name = name, seed = seed, size = size, isGroup = isGroup)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                loading = { InitialsAvatar(name, seed, size, isGroup) },
                error = { InitialsAvatar(name, seed, size, isGroup) },
            )
        }

        if (showPresence && isOnline) {
            val dot = (size.value * 0.28f).coerceIn(9f, 16f).dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(dot - 4.dp)
                        .clip(CircleShape)
                        .background(PingTheme.colors.online),
                )
            }
        }
    }
}

@Composable
private fun InitialsAvatar(name: String, seed: String, size: Dp, isGroup: Boolean) {
    val background = remember(seed) { avatarColorFor(seed) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isGroup && name.isBlank()) "#" else TextUtils.initials(name),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
            maxLines = 1,
        )
    }
}

/**
 * A stable colour per identity.
 *
 * The palette is hand-picked rather than generated: every entry has enough contrast against
 * white text to clear WCAG AA at the sizes avatars are drawn, which a random hue would not.
 */
private val AvatarPalette = listOf(
    Color(0xFF00695C), Color(0xFF00796B), Color(0xFF00838F), Color(0xFF0277BD),
    Color(0xFF1565C0), Color(0xFF283593), Color(0xFF4527A0), Color(0xFF6A1B9A),
    Color(0xFFAD1457), Color(0xFFC62828), Color(0xFFD84315), Color(0xFFEF6C00),
    Color(0xFF827717), Color(0xFF33691E), Color(0xFF2E7D32), Color(0xFF37474F),
)

fun avatarColorFor(seed: String): Color =
    AvatarPalette[(seed.hashCode().absoluteValue) % AvatarPalette.size]

/** A cluster of overlapping avatars, used for group rows and call participants. */
@Composable
fun AvatarStack(
    entries: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    max: Int = 3,
) {
    Box(modifier) {
        val shown = entries.take(max)
        shown.forEachIndexed { index, (name, url) ->
            Avatar(
                name = name,
                photoUrl = url,
                size = size,
                modifier = Modifier
                    .offset(x = (index * (size.value * 0.62f)).dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}
