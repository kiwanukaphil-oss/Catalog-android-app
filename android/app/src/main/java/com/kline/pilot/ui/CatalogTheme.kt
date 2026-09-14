package com.kline.pilot.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Source: web app/app/globals.css workspace overrides. Update both appearances together.
private val CatalogLight = lightColorScheme(
    primary = Color(0xFF24465A), onPrimary = Color.White,
    background = Color(0xFFF6F5F2), onBackground = Color(0xFF242C31),
    surface = Color.White, onSurface = Color(0xFF242C31),
    surfaceVariant = Color(0xFFEEEDEA), onSurfaceVariant = Color(0xFF697277),
    secondaryContainer = Color(0xFFE6EDEF), onSecondaryContainer = Color(0xFF24465A),
    outline = Color(0xFFC8CFCD), outlineVariant = Color(0xFFDFE1DC),
    surfaceContainer = Color(0xFFF6F5F2)
)

private val CatalogDark = darkColorScheme(
    primary = Color(0xFFBBD7E3), onPrimary = Color(0xFF192D38),
    background = Color(0xFF182024), onBackground = Color(0xFFEEF0EC),
    surface = Color(0xFF202B30), onSurface = Color(0xFFEEF0EC),
    surfaceVariant = Color(0xFF283337), onSurfaceVariant = Color(0xFFAEB8B9),
    secondaryContainer = Color(0xFF31434C), onSecondaryContainer = Color(0xFFE4EEF2),
    outline = Color(0xFF536368), outlineVariant = Color(0xFF364247),
    surfaceContainer = Color(0xFF182024)
)

/** Apply the web workspace palette and restrained corners to native controls in every screen. */
@Composable fun CatalogTheme(darkAppearance: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkAppearance) CatalogDark else CatalogLight,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(9.dp), large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp)
        ),
        content = content
    )
}
