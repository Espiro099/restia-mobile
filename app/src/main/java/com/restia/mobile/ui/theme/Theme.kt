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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restia.mobile.R

/**
 * Identidad de Restia, propuesta 04 "Servicio claro" (Figma): naranja de acción, crema de fondo, papel para las
 * tarjetas y tinta café. Alto contraste: se usa de pie, con prisa y con poca luz.
 */
private object Palette {
    val Orange = Color(0xFFEF5B16)
    val OrangeDeep = Color(0xFFBA4100)
    val OrangeSoft = Color(0xFFFFE8D7)
    val Cream = Color(0xFFFFF5EC)
    val Paper = Color(0xFFFFFCF9)
    val Sand = Color(0xFFE8D3C1)
    val SandSoft = Color(0xFFF4E8DC)
    val Ink = Color(0xFF2B1A15)
    val Muted = Color(0xFF81675A)
    val Brown = Color(0xFF4A1A12)
    val Gold = Color(0xFFA66A00)
    val GoldSoft = Color(0xFFF8E9BD)
    val Green = Color(0xFF246E40)
    val GreenSoft = Color(0xFFDDF3E5)
    val Blue = Color(0xFF2B7FC4)
    val Red = Color(0xFFB3261E)
    val RedSoft = Color(0xFFF8DEDA)
}

private val Light = lightColorScheme(
    primary = Palette.Orange,
    onPrimary = Palette.Paper,
    primaryContainer = Palette.OrangeSoft,
    onPrimaryContainer = Palette.OrangeDeep,
    secondary = Palette.Gold,
    onSecondary = Palette.Paper,
    secondaryContainer = Palette.GoldSoft,
    onSecondaryContainer = Color(0xFF805400),
    tertiary = Palette.Green,
    onTertiary = Palette.Paper,
    tertiaryContainer = Palette.GreenSoft,
    onTertiaryContainer = Palette.Green,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Cream,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.SandSoft,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Color(0xFFFFF9F3),
    surfaceContainer = Palette.SandSoft,
    surfaceContainerHigh = Color(0xFFF0E1D3),
    surfaceContainerHighest = Palette.Sand,
    outline = Palette.Muted,
    outlineVariant = Palette.Sand,
    error = Palette.Red,
    errorContainer = Palette.RedSoft,
    onErrorContainer = Color(0xFF6B1410),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF27A45),
    onPrimary = Color(0xFF2B0E00),
    primaryContainer = Color(0xFF5A2410),
    onPrimaryContainer = Color(0xFFFFD3BE),
    secondary = Color(0xFFF2C46E),
    onSecondary = Color(0xFF412C00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFFBE3A8),
    tertiary = Color(0xFF8FD3A6),
    onTertiary = Color(0xFF0E2E19),
    tertiaryContainer = Color(0xFF1E3A28),
    onTertiaryContainer = Color(0xFFB9EBCB),
    background = Color(0xFF1C1411),
    onBackground = Color(0xFFF6E8DD),
    surface = Color(0xFF1C1411),
    onSurface = Color(0xFFF6E8DD),
    surfaceVariant = Color(0xFF34261F),
    onSurfaceVariant = Color(0xFFC9B3A6),
    surfaceContainerLowest = Color(0xFF2A1E19),
    surfaceContainerLow = Color(0xFF241A16),
    surfaceContainer = Color(0xFF2E221C),
    surfaceContainerHigh = Color(0xFF382A23),
    surfaceContainerHighest = Color(0xFF44342B),
    outline = Color(0xFFA48D80),
    outlineVariant = Color(0xFF4A3A31),
    error = Color(0xFFF2877D),
    errorContainer = Color(0xFF5C1712),
    onErrorContainer = Color(0xFFFFD9D4),
)

/** Fondo, texto y borde de una tarjeta de estado (mesa, aviso). `dashed`: aún no confirmado o pendiente. */
@Immutable
data class Tone(val container: Color, val content: Color, val border: Color, val dashed: Boolean = false)

/** Los estados de mesa de la propuesta, cada uno con su tarjeta. */
@Immutable
data class TableTones(
    val free: Tone,
    val possible: Tone,
    val noOrder: Tone,
    val waiting: Tone,
    val eating: Tone,
    val check: Tone,
    val cleaning: Tone,
    val off: Tone,
)

/** Colores con significado propio, fuera del esquema de Material. */
@Immutable
data class RestiaColors(
    val brand: Color,
    /** Café de la marca como fondo (encabezado de la cuenta); lleva texto claro encima. */
    val brandDeep: Color,
    /** Texto de títulos: tinta en claro, crema en oscuro. */
    val heading: Color,
    /** Verde, oro, rojo y gris de estado, para texto y puntos (contrastan sobre el fondo). */
    val free: Color,
    val busy: Color,
    val attention: Color,
    val idle: Color,
    val tables: TableTones,
    /** Fondo del aviso accionable ("Mesa 8 · orden lista"). */
    val notice: Tone,
)

private val LightExtras = RestiaColors(
    brand = Palette.Orange,
    brandDeep = Palette.Brown,
    heading = Palette.Ink,
    free = Palette.Green,
    busy = Palette.Gold,
    attention = Palette.Red,
    idle = Palette.Muted,
    tables = TableTones(
        free = Tone(Palette.Paper, Palette.Ink, Palette.Sand),
        possible = Tone(Palette.Paper, Palette.Blue, Palette.Blue, dashed = true),
        noOrder = Tone(Palette.GoldSoft, Color(0xFF805400), Palette.Sand),
        waiting = Tone(Palette.OrangeSoft, Palette.OrangeDeep, Palette.Sand),
        eating = Tone(Palette.GreenSoft, Palette.Green, Palette.Sand),
        check = Tone(Palette.Orange, Palette.Paper, Palette.Sand),
        cleaning = Tone(Palette.SandSoft, Palette.Muted, Palette.Muted, dashed = true),
        off = Tone(Palette.SandSoft, Palette.Muted, Palette.Sand),
    ),
    notice = Tone(Palette.GoldSoft, Palette.Ink, Palette.GoldSoft),
)

private val DarkExtras = RestiaColors(
    brand = Color(0xFFF27A45),
    brandDeep = Color(0xFF5A2E1B),
    heading = Color(0xFFF6E8DD),
    free = Color(0xFF8FD3A6),
    busy = Color(0xFFF2C46E),
    attention = Color(0xFFF2877D),
    idle = Color(0xFFC9B3A6),
    tables = TableTones(
        free = Tone(Color(0xFF2A1E19), Color(0xFFF6E8DD), Color(0xFF4A3A31)),
        possible = Tone(Color(0xFF2A1E19), Color(0xFF8CC2F0), Color(0xFF8CC2F0), dashed = true),
        noOrder = Tone(Color(0xFF3D3016), Color(0xFFF2C46E), Color(0xFF4A3A31)),
        waiting = Tone(Color(0xFF4A2614), Color(0xFFFFB38A), Color(0xFF4A3A31)),
        eating = Tone(Color(0xFF1E3A28), Color(0xFF8FD3A6), Color(0xFF4A3A31)),
        check = Tone(Color(0xFFEF5B16), Color(0xFFFFFCF9), Color(0xFFEF5B16)),
        cleaning = Tone(Color(0xFF2E241F), Color(0xFFC9B3A6), Color(0xFFA48D80), dashed = true),
        off = Tone(Color(0xFF241A16), Color(0xFF8A7568), Color(0xFF4A3A31)),
    ),
    notice = Tone(Color(0xFF3D3016), Color(0xFFF6E8DD), Color(0xFF3D3016)),
)

val LocalRestiaColors = staticCompositionLocalOf { LightExtras }

/** Manrope (OFL, res/font): la tipografía de la propuesta, de fuente variable con todos sus pesos. */
@OptIn(ExperimentalTextApi::class)
val Manrope = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
        // Sin el ajuste explícito, la fuente variable se queda en su peso mínimo.
        Font(R.font.manrope, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

/** Tamaños de la propuesta: títulos de 18–24, cuerpo de 14 y etiquetas de 11–13. */
private val RestiaTypography = Typography(
    displayLarge = style(48, 56, FontWeight.Bold),
    displayMedium = style(44, 52, FontWeight.Bold),
    displaySmall = style(40, 48, FontWeight.Bold, -0.5),
    headlineLarge = style(30, 38, FontWeight.Bold),
    headlineMedium = style(26, 34, FontWeight.Bold),
    headlineSmall = style(24, 34, FontWeight.Bold),
    titleLarge = style(19, 24, FontWeight.Bold),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(15, 22, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Bold),
    labelMedium = style(12, 17, FontWeight.SemiBold),
    labelSmall = style(11, 15, FontWeight.Medium),
)

private val RestiaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
