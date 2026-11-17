package com.restia.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Identidad de Restia (propuesta de interfaz): terracota de la flama, mostaza, verde olivo y crema.
 * Cálida y de alto contraste: se usa de pie, con prisa y con poca luz.
 */
private object Palette {
    val Terracotta = Color(0xFFC0532A)
    val TerracottaDark = Color(0xFF8E3A1B)
    val TerracottaLight = Color(0xFFF6DDD0)
    val Brown = Color(0xFF4A2516)
    val Mustard = Color(0xFFE3A23B)
    val MustardLight = Color(0xFFFBEBC9)
    val Olive = Color(0xFF5F8A4A)
    val OliveLight = Color(0xFFDDEBD0)
    val Cream = Color(0xFFFBF6F0)
    val Sand = Color(0xFFF1E6DA)
    val SandDeep = Color(0xFFE6D5C3)
    val Ink = Color(0xFF2B1D16)
    val InkSoft = Color(0xFF6B5A4E)
    val Line = Color(0xFFD6C4B3)
}

private val Light = lightColorScheme(
    primary = Palette.Terracotta,
    onPrimary = Color.White,
    primaryContainer = Palette.TerracottaLight,
    onPrimaryContainer = Palette.TerracottaDark,
    secondary = Color(0xFF9A6A10),
    onSecondary = Color.White,
    secondaryContainer = Palette.MustardLight,
    onSecondaryContainer = Color(0xFF5C3B00),
    tertiary = Palette.Olive,
    onTertiary = Color.White,
    tertiaryContainer = Palette.OliveLight,
    onTertiaryContainer = Color(0xFF1F3A14),
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Cream,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Sand,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F1E9),
    surfaceContainer = Palette.Sand,
    surfaceContainerHigh = Color(0xFFECDFD1),
    surfaceContainerHighest = Palette.SandDeep,
    outline = Palette.Line,
    outlineVariant = Color(0xFFE8DACB),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF6B1410),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF0A07D),
    onPrimary = Color(0xFF4F1A05),
    primaryContainer = Palette.TerracottaDark,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFF2C46E),
    onSecondary = Color(0xFF412C00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE2A8),
    tertiary = Color(0xFFA9CF93),
    onTertiary = Color(0xFF173610),
    tertiaryContainer = Color(0xFF365A27),
    onTertiaryContainer = Color(0xFFC5EBAE),
    background = Color(0xFF1C1410),
    onBackground = Color(0xFFF1E3D8),
    surface = Color(0xFF1C1410),
    onSurface = Color(0xFFF1E3D8),
    surfaceVariant = Color(0xFF3A2C24),
    onSurfaceVariant = Color(0xFFD7C2B3),
    surfaceContainerLowest = Color(0xFF15100C),
    surfaceContainerLow = Color(0xFF241A15),
    surfaceContainer = Color(0xFF2A1F19),
    surfaceContainerHigh = Color(0xFF352821),
    surfaceContainerHighest = Color(0xFF40312A),
    outline = Color(0xFF9F8C7E),
    outlineVariant = Color(0xFF53433A),
)

/** Colores con significado propio, fuera del esquema de Material: estados de mesa y la marca. */
@Immutable
data class RestiaColors(
    val brand: Color,
    /** Café de la marca como fondo (encabezado de la cuenta, arco del acceso); lleva texto blanco encima. */
    val brandDeep: Color,
    /** Café de la marca como texto (títulos, logo): café sobre crema, crema cálida sobre el fondo oscuro. */
    val heading: Color,
    val free: Color,
    val busy: Color,
    val attention: Color,
    val idle: Color,
)

private val LightExtras = RestiaColors(
    brand = Palette.Terracotta,
    brandDeep = Palette.Brown,
    heading = Palette.Brown,
    free = Palette.Olive,
    busy = Palette.Mustard,
    attention = Color(0xFFD2452F),
    idle = Color(0xFFA79A8E),
)

private val DarkExtras = RestiaColors(
    brand = Color(0xFFF0A07D),
    // Más claro que el fondo para que el encabezado de la cuenta se distinga; el blanco sigue contrastando.
    brandDeep = Color(0xFF5A2E1B),
    heading = Color(0xFFF3D9C6),
    free = Color(0xFF8DBB74),
    busy = Color(0xFFE9B65A),
    attention = Color(0xFFF07A64),
    idle = Color(0xFF8A7D72),
)

val LocalRestiaColors = staticCompositionLocalOf { LightExtras }

/** Serif para la marca y los títulos, como en la propuesta; el texto de trabajo queda en la sans del sistema. */
val BrandFamily: FontFamily = FontFamily.Serif

private val RestiaTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = BrandFamily, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = BrandFamily, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = BrandFamily, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = BrandFamily, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

/** Logotipo: la palabra "Restia" en serif, con la flama al lado. */
val WordmarkStyle = TextStyle(fontFamily = BrandFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)

private val RestiaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun RestiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalRestiaColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            typography = RestiaTypography,
            shapes = RestiaShapes,
            content = content,
        )
    }
}

/** Atajo: `Restia.colors.free`. */
object Restia {
    val colors: RestiaColors
        @Composable get() = LocalRestiaColors.current
}
