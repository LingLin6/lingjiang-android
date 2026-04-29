package com.linglin.lingjiang.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LingJiangColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF254441),
    onPrimary = Color.White,
    secondary = Color(0xFFD64550),
    onSecondary = Color.White,
    tertiary = Color(0xFF4A6FA5),
    background = Color(0xFFF7F7F2),
    onBackground = Color(0xFF151715),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151715),
    surfaceVariant = Color(0xFFE7ECE8),
    onSurfaceVariant = Color(0xFF444A46),
    error = Color(0xFFB3261E),
)

@Composable
fun LingJiangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LingJiangColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
