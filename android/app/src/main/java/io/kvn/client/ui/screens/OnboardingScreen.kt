package io.kvn.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.ui.components.pressable
import io.kvn.client.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Что сделать после инструкции. */
enum class OnboardingNext { NONE, ADD_SUBSCRIPTION, LOGIN }

private const val PAGES = 6

/**
 * Вводная инструкция простыми словами: что такое подписка, как включить VPN,
 * что делает авто-режим и белый список. Первые настройки выбираются прямо
 * здесь, поэтому после неё достаточно добавить подписку и нажать кнопку.
 */
@Composable
fun OnboardingScreen(
    subscriptionCount: Int,
    initialAuto: Boolean,
    initialWhitelist: Boolean,
    onFinish: (auto: Boolean, whitelist: Boolean, next: OnboardingNext) -> Unit,
) {
    var auto by remember { mutableStateOf(initialAuto) }
    var whitelist by remember { mutableStateOf(initialWhitelist) }
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onFinish(auto, whitelist, OnboardingNext.NONE)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("KVN", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!last) {
                TextButton(onClick = { onFinish(auto, whitelist, OnboardingNext.NONE) }) {
                    Text("Пропустить", color = Palette.TextMuted)
                }
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            // Соседние страницы слегка уменьшены и прозрачны — перелистывание «глубже».
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - offset * 0.6f
                        scaleX = 1f - offset * 0.08f
                        scaleY = 1f - offset * 0.08f
                    },
            ) {
                when (page) {
                    0 -> Page(
                        Icons.Rounded.Shield,
                        "Здравствуйте!",
                        "KVN открывает заблокированные сайты и приложения и защищает соединение.\n\nНастроить нужно один раз — дальше достаточно одной кнопки. Листайте вправо, это займёт минуту.",
                    )
                    1 -> Page(
                        Icons.Rounded.Link,
                        "Шаг 1. Добавьте подписку",
                        "Подписка — это ссылка со списком серверов. Её присылает тот, кто настроил вам VPN.",
                    ) {
                        Bullet("Нажмите на ссылку в сообщении — KVN откроется и предложит её добавить.")
                        Bullet("Или скопируйте ссылку и откройте KVN — окно добавления появится само.")
                        Bullet("Или войдите в sub-lab по логину и паролю — подписки подтянутся сами.")
                        if (subscriptionCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("✓ Подписки уже добавлены: $subscriptionCount", color = Palette.Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    2 -> Page(
                        Icons.Rounded.PowerSettingsNew,
                        "Шаг 2. Нажмите большую кнопку",
                        "Круглая кнопка на главном экране включает и выключает VPN.",
                    ) {
                        Bullet("В первый раз телефон спросит разрешение на VPN — нажмите «ОК».")
                        Bullet("Зелёная кнопка и надпись «Подключено» — всё работает.")
                        Bullet("Значок ключа в строке состояния — VPN включён.")
                        Bullet("Включать можно и из шторки: KVN предложит добавить туда плитку.")
                    }
                    3 -> Page(
                        Icons.Rounded.AutoMode,
                        "Авто-режим",
                        "KVN сам выберет сервер, который работает лучше всего. Каждые 5 минут он проверяет связь, а если сервер перестал отвечать — переключается на другой.",
                    ) {
                        Choice("Включить авто-режим", "Рекомендуем: ничего не нужно выбирать вручную", auto) { auto = it }
                    }
                    4 -> Page(
                        Icons.Rounded.AccountBalance,
                        "Банки и Госуслуги",
                        "Некоторые сайты и приложения — банки, Госуслуги, маркетплейсы — не работают через VPN или работают медленнее. Их можно пускать напрямую.",
                    ) {
                        Choice("Пускать их напрямую", "Список сайтов можно изменить в настройках", whitelist) { whitelist = it }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Приложения банков можно отметить в «Настройки → Приложения через VPN» одним нажатием.",
                            color = Palette.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                    else -> Page(
                        Icons.Rounded.Home,
                        "Что где",
                        "Внизу четыре вкладки:",
                    ) {
                        TabHint(Icons.Rounded.Home, "Главная", "кнопка включения и текущий сервер")
                        TabHint(Icons.Rounded.Dns, "Серверы", "список серверов, пинг и выбор вручную")
                        TabHint(Icons.Rounded.Radar, "Проверка", "открываются ли нужные сайты")
                        TabHint(Icons.Rounded.Settings, "Настройки", "аккаунт, приложения, Wi-Fi и всё для опытных")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Инструкцию можно пройти снова — кнопка «?» на главном экране.",
                            color = Palette.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        Dots(pager.currentPage)
        Spacer(Modifier.height(16.dp))

        Column(Modifier.padding(horizontal = 20.dp)) {
            if (last) {
                if (subscriptionCount == 0) {
                    BigButton("Добавить подписку", accent = true) { onFinish(auto, whitelist, OnboardingNext.ADD_SUBSCRIPTION) }
                    Spacer(Modifier.height(8.dp))
                    BigButton("Войти в sub-lab") { onFinish(auto, whitelist, OnboardingNext.LOGIN) }
                    Spacer(Modifier.height(8.dp))
                    BigButton("Позже", subtle = true) { onFinish(auto, whitelist, OnboardingNext.NONE) }
                } else {
                    BigButton("Готово", accent = true) { onFinish(auto, whitelist, OnboardingNext.NONE) }
                }
            } else {
                BigButton("Далее", accent = true) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, text: String, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.Violet.copy(alpha = 0.45f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceHighest)
                    .border(1.dp, Palette.Violet.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Palette.VioletSoft, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(text, fontSize = 16.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth()) { extra() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(Palette.Cyan),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp, color = Palette.TextPrimary, lineHeight = 21.sp)
    }
}

@Composable
private fun TabHint(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, fontSize = 13.sp, color = Palette.TextSecondary)
        }
    }
}

/** Крупный переключатель: всю карточку удобно нажимать пальцем. */
@Composable
private fun Choice(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val border by animateColorAsState(if (checked) Palette.Violet else Palette.Stroke, tween(250), label = "choiceBorder")
    val background by animateColorAsState(if (checked) Palette.Violet.copy(alpha = 0.14f) else Palette.Surface, tween(250), label = "choiceBg")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .pressable { onChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = Palette.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        val knob by animateDpAsState(if (checked) 22.dp else 0.dp, tween(220), label = "knob")
        Box(
            Modifier
                .width(48.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (checked) Palette.Violet else Palette.SurfaceHighest)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .padding(start = knob)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Palette.TextPrimary),
            )
        }
    }
}

@Composable
private fun Dots(current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(PAGES) { index ->
            val active = index == current
            val width by animateDpAsState(if (active) 22.dp else 7.dp, tween(280), label = "dot")
            val color by animateColorAsState(if (active) Palette.VioletSoft else Palette.Stroke, tween(280), label = "dotColor")
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(7.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun BigButton(title: String, accent: Boolean = false, subtle: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    accent -> Palette.Violet
                    subtle -> Color.Transparent
                    else -> Palette.Surface
                },
            )
            .then(if (!accent && !subtle) Modifier.border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp)) else Modifier)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (subtle) Palette.TextSecondary else Palette.TextPrimary,
        )
    }
}
