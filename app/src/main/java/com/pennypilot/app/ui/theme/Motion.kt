package com.pennypilot.app.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion shared by application-owned interactions. Material controls keep their native motion.
 * Spatial springs retarget from their current velocity; effects never overshoot color or alpha.
 * All specs run on Compose's animation clock, including Android's animator duration scale.
 * Do not use these durations to delay a save, network request, focus change, or navigation.
 */
object PennyMotion {
    const val Feedback = 140
    const val Enter = 180
    const val Exit = 90
    const val Duration = 280

    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 500f)

    fun <T> effects(): TweenSpec<T> = tween(durationMillis = 160, easing = FastOutSlowInEasing)
}
