package com.najah.camapsguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF37D67A),
    onPrimary = Color(0xFF04120A),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF06101F),
    background = Color(0xFF0B1014),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121A21),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B252F),
    onSurfaceVariant = Color(0xFF9FB0C0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF200404)
)

@Composable
fun GuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
