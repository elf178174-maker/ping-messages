package com.ping.messenger.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val PingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Chat bubble corners. The corner adjacent to the speaker is tightened on the last bubble of a
 * run, which is what gives a group of consecutive messages its visual "tail" without drawing an
 * actual tail shape.
 */
object BubbleShapes {
    private val Round = 18.dp
    private val Tight = 6.dp

    fun outgoing(isLastInGroup: Boolean) = RoundedCornerShape(
        topStart = Round,
        topEnd = Round,
        bottomEnd = if (isLastInGroup) Tight else Round,
        bottomStart = Round,
    )

    fun incoming(isLastInGroup: Boolean) = RoundedCornerShape(
        topStart = Round,
        topEnd = Round,
        bottomEnd = Round,
        bottomStart = if (isLastInGroup) Tight else Round,
    )

    val system = RoundedCornerShape(CornerSize(12.dp))
    val attachment = RoundedCornerShape(14.dp)
}
