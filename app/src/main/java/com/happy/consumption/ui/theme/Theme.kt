package com.happy.consumption.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF2196F3)
val AutoCard = Color.White
val ManualCard = Color(0xFFF5F5F5)
val DeleteBorder = Color(0xFF9E9E9E)
private val Colors = lightColorScheme(
    primary = PrimaryBlue, onPrimary = Color.White,
    background = Color(0xFFEEEEF2), surface = Color.White,
    onSurface = Color(0xFF191C24), secondaryContainer = Color(0xFFEEEEFF),
    onSecondaryContainer = Color(0xFF393184),
)

@Composable
fun HappyConsumptionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
