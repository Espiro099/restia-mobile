package com.restia.mobile.ui.orders

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.restia.mobile.R

private val StepDone = Color(0xFF2F9E5B)
private val StepIdleBorder = Color(0xFFD5B79C)
private val OnStep = Color(0xFFFFFCF9)

/**
 * Qué tanto de la ruta (del primer paso al último) lleva recorrido la ronda: 0 al recibirla, 1 al servirla.
 * Un paso fuera de rango se acota.
 */
fun routeFraction(stage: Int, steps: Int): Float =
    if (steps <= 1) 1f else stage.coerceIn(0, steps - 1).toFloat() / (steps - 1)

/** ¿El teléfono tiene las animaciones apagadas (Opciones de desarrollador o accesibilidad)? */
@Composable
private fun animationsOff(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Seguimiento de la ronda (propuesta 04, "Seguimiento de orden"): el recorrido de la comida de cocina a la
 * mesa. Cuatro paradas unidas por una ruta; la ruta se va llenando hasta la parada actual, con un punto que
 * marca por dónde va, y la parada actual late suave mientras espera. Cumplido en verde, actual en naranja,
 * pendiente en crema con reloj. Con las animaciones del teléfono apagadas se muestra quieto.
 */
@Composable
fun RoundTracker(stage: Int, startedAt: String?, modifier: Modifier = Modifier) {
    val steps = ROUND_STEPS
    val still = animationsOff()
    val target = routeFraction(stage, steps.size)
    // Al abrir la mesa la ruta se recorre desde el inicio; después, de la parada anterior a la nueva.
    val travelled = remember { Animatable(if (still) target else 0f) }
    LaunchedEffect(target, still) {
        if (still) travelled.snapTo(target) else travelled.animateTo(target, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val finished = stage >= steps.lastIndex
    val pulse = if (still || finished) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "parada-actual")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "latido").value
    }
    val stepLabel = stringResource(steps[stage.coerceIn(0, steps.lastIndex)])
    val description = stringResource(R.string.track_a11y, stepLabel, stage.coerceIn(0, steps.lastIndex) + 1, steps.size)

    BoxWithConstraints(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        // La ruta va del centro de la primera parada al de la última: medio ancho de columna a cada lado.
        val inset = maxWidth / (steps.size * 2)
        val length = maxWidth - inset * 2
        Box(Modifier.padding(start = inset, top = 21.5.dp).width(length).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.padding(start = inset, top = 21.5.dp).width(length * travelled.value).height(3.dp).clip(CircleShape).background(StepDone))
        // Punto de la ruta: va al frente de lo recorrido mientras se mueve y se oculta bajo la parada al llegar.
        if (!still && travelled.value < 1f) {
            Box(
                Modifier
                    // Lambda: la posición se lee al dibujar, sin recomponer en cada cuadro de la animación.
                    .offset { IntOffset((inset + length * travelled.value - 5.dp).roundToPx(), 18.dp.roundToPx()) }
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(StepDone)
                    .border(2.dp, OnStep, CircleShape),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            steps.forEachIndexed { i, label ->
                val done = i < stage || (i == stage && i == steps.lastIndex)
                val current = i == stage && !done
                // Una parada se pinta cuando la ruta ya llegó a ella, no antes.
                val reached = travelled.value + 0.001f >= routeFraction(i, steps.size)
                val fill by animateColorAsState(
                    when {
                        done && reached -> StepDone
                        current && reached -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.background
                    },
                    animationSpec = tween(if (still) 0 else 300),
                    label = "parada-$i",
                )
                val lit = (done || current) && reached
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (current && reached && pulse > 0f) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .graphicsLayer {
                                        scaleX = 1f + pulse * 0.45f
                                        scaleY = 1f + pulse * 0.45f
                                        alpha = (1f - pulse) * 0.35f
                                    }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(fill)
                                .border(1.dp, if (lit) fill else StepIdleBorder, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Crossfade(lit, label = "icono-$i") { on ->
                                Icon(
                                    painterResource(if (on) R.drawable.ic_state_check else R.drawable.ic_clock),
                                    contentDescription = null,
                                    tint = if (on) OnStep else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Normal),
                        textAlign = TextAlign.Center,
                    )
                    val detail = when {
                        i == 0 -> startedAt
                        i > stage -> stringResource(R.string.track_pending)
                        else -> null
                    }
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
