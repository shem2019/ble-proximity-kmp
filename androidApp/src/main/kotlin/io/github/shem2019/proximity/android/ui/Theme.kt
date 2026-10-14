package io.github.shem2019.proximity.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.shem2019.proximity.proximity.ProximityZone

@Composable
fun ProximityTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Fixed zone colours, so a zone reads the same whatever the wallpaper-based theme. */
fun ProximityZone.color(): Color = when (this) {
    ProximityZone.Near -> Color(0xFF2E7D32)
    ProximityZone.Mid -> Color(0xFFE09B00)
    ProximityZone.Far -> Color(0xFF607D8B)
}
