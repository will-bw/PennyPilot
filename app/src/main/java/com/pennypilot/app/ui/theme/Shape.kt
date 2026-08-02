package com.pennypilot.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val PennyPilotShapes = Shapes(
    extraLarge = RoundedCornerShape(22.dp),   // cards
    large = RoundedCornerShape(19.dp),        // FAB / bubbles
    medium = RoundedCornerShape(15.dp),       // buttons / icon containers
    small = RoundedCornerShape(8.dp),         // chips
    extraSmall = RoundedCornerShape(6.dp),
)
