package com.justan.lowkeycam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Cream = Color(0xFFFAF6EF)
val Card = Color(0xFFFFFCF6)
val Sand = Color(0xFFEDE5D6)
val Ink = Color(0xFF1E1B16)
val Forest = Color(0xFF2B4B3A)
val Moss = Color(0xFF5E7F68)
val Muted = Color(0xFF8A8172)
val RecordRed = Color(0xFFC4453F)

val Serif = FontFamily.Serif

private val Colors = lightColorScheme(
    primary = Forest,
    onPrimary = Cream,
    secondary = Moss,
    onSecondary = Cream,
    background = Cream,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Ink,
    error = RecordRed,
)

private val Type = Typography(
    displayLarge = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
    ),
)

@Composable
fun LowKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
