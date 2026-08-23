package com.pennypilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightPrimarySoft,
    onPrimaryContainer = LightPrimaryInk,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightSub,
    outline = LightHairline,
    error = LightIncome,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkPrimarySoft,
    onPrimaryContainer = DarkPrimaryInk,
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkSub,
    outline = DarkHairline,
    error = DarkIncome,
)

@Composable
fun PennyPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PennyPilotTypography,
        shapes = PennyPilotShapes,
        content = content
    )
}

/** 靛蓝品牌渐变（主色 → 深色端），明暗主题自适配 */
val MaterialTheme.priGradient: List<Color>
    @Composable get() = listOf(
        colorScheme.primary,
        if (colorScheme.primary == DarkPrimary) DarkPrimaryDeep else LightPrimaryDeep
    )

/** 三级弱化文字色（对应原型 --faint） */
val MaterialTheme.faintColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkFaint else LightFaint

/** 服务健康绿（对应原型 --green） */
val MaterialTheme.successColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkGreen else LightGreen

/** 收入暖红浅底（对应原型 --red-soft），暗色为半透明红 */
val MaterialTheme.incomeSoftColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkIncomeSoft else LightIncomeSoft

/** 健康绿浅底（对应原型 --green-soft），暗色为半透明绿 */
val MaterialTheme.successSoftColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkGreenSoft else LightGreenSoft

/** 待确认琥珀（对应原型 --amber），暗色为亮琥珀 */
val MaterialTheme.pendingAmberColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkAmber else LightAmber

/** 待确认琥珀浅底（对应原型 --amber-soft），暗色为半透明琥珀 */
val MaterialTheme.pendingAmberSoftColor: Color
    @Composable get() = if (colorScheme.primary == DarkPrimary) DarkAmberSoft else LightAmberSoft
