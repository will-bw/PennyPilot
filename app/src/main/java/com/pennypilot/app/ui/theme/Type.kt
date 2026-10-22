package com.pennypilot.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val SerifFamily = FontFamily.Serif

val PennyPilotTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 48.3.sp),
    displayMedium = TextStyle(fontSize = 37.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = SerifFamily, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontFamily = SerifFamily, fontSize = 21.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

// 账目专用文字角色；随系统字号缩放。
val EyebrowStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
val TxTitleStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.W600)
val TxSubStyle = TextStyle(fontSize = 13.sp)
val TxAmountStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
val DayHeadDateStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
val DayHeadInfoStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600)
val ChipTextStyle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.W600)
val PillTextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600)
val CatLabelStyle = TextStyle(fontSize = 12.sp)
val HeroAmountStyle = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 48.3.sp)
val HeroYenStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.W600)
val PageSubStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
