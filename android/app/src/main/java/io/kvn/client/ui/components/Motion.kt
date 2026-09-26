package io.kvn.client.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/** Пружина для интерфейса: быстрая и без «резины». */
fun <T> uiSpring() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * Нажатие с лёгким «вдавливанием»: элемент мягко уменьшается под пальцем и
 * пружинит обратно. Используется вместо обычного clickable у карточек и кнопок.
 */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Плавное появление при первом показе: подъём снизу и проявление. [index]
 * задаёт ступенчатую задержку для списков.
 */
@Composable
fun AppearIn(index: Int = 0, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(420, delayMillis = 40 * index.coerceAtMost(8))) +
            slideInVertically(tween(480, delayMillis = 40 * index.coerceAtMost(8))) { it / 6 },
    ) {
        content()
    }
}

/** Текст, который при смене значения плавно «прокручивается» (скорость, пинг, таймер). */
@Composable
fun RollingText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(200)))
        },
        modifier = modifier,
        label = "rolling",
    ) { value ->
        Text(value, color = color, fontSize = fontSize, fontWeight = fontWeight)
    }
}
