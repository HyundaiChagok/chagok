package app.chagok.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.ValueSource
import app.chagok.core.model.StateItem
import app.chagok.core.record.Canonical
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.EnergyGauge
import app.chagok.core.rental.RentalState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** 앱 창이 넓은지(가로 1200dp 이상). Pleos는 앱 두 개를 나란히 띄우면 세로 창을 준다(디자인 가이드 Display Layout). */
val LocalWide = staticCompositionLocalOf { true }

/**
 * Primary는 화면마다 하나이고 잉크색으로 채운다. Danger는 되돌릴 수 없는 직원 동작(무효 처리)에만 쓴다.
 * 종류가 바뀌면(남은 것이 없어져 보조 버튼이 주 버튼이 되면) 색이 부드럽게 옮겨 간다.
 */
enum class ButtonKind { Primary, Secondary, Danger }

/** 버튼. Pleos 기본 버튼처럼 누르는 동안 95%로 줄어든다(디자인 가이드 Interaction). 높이는 88dp 이상이다. */
@Composable
fun ChagokButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val c = ChagokTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(if (pressed) 90 else 180), label = "press")
    val container by animateColorAsState(
        when (kind) {
            ButtonKind.Primary -> c.primary
            ButtonKind.Secondary -> c.sunken
            ButtonKind.Danger -> c.badSoft
        },
        tween(240),
        label = "buttonFill",
    )
    val content by animateColorAsState(
        when (kind) {
            ButtonKind.Primary -> c.onPrimary
            ButtonKind.Secondary -> c.ink
            ButtonKind.Danger -> c.bad
        },
        tween(240),
        label = "buttonText",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = c.sunken,
            disabledContentColor = c.ink3,
        ),
        contentPadding = PaddingValues(horizontal = 44.dp),
        modifier = modifier
            .heightIn(min = 88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 버튼 줄. 주 버튼은 늘 왼쪽 아래, 운전석에서 가까운 쪽에 둔다. 좁은 창에서 넘치면 다음 줄로 내려간다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** 화면 위 띠. 이름(길게 누르면 직원 화면), 지금 단계, 실차 값이 아니면 작은 표시(요구사항 QUR-04). 값 출처 전문은 직원 화면에만 둔다. */
@Composable
fun AppHeader(state: RentalState, source: ValueSource, onStaff: () -> Unit) {
    val c = ChagokTheme.colors
    val wide = LocalWide.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
        Text(
            "차곡",
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
            modifier = Modifier
                .semantics { contentDescription = "차곡" }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onStaff() }) }
                .padding(vertical = 16.dp)
                .padding(end = 24.dp),
        )
        Journey(state, compact = !wide, modifier = Modifier.weight(1f))
        source.tag?.let { tag -> Tag(tag, color = c.ink2, container = c.sunken) }
    }
}

private val STEPS = listOf("인수 기록", "대여 중", "반납 체크", "반납 완료")

private fun RentalState.step(): Int? = when (this) {
    RentalState.IDLE, RentalState.VOIDED -> null
    RentalState.NOTICE, RentalState.PICKUP -> 0
    RentalState.IN_RENTAL -> 1
    RentalState.RETURN_CHECK, RentalState.RETURN -> 2
    RentalState.RETURNED -> 3
}

/** 한 번의 대여에서 지금 어디쯤인지. 현재 단계는 잉크색으로 채운다. 좁은 창에서는 지금 단계 이름만 쓴다. */
@Composable
private fun Journey(state: RentalState, compact: Boolean, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    if (state == RentalState.VOIDED) {
        Row(modifier) { Tag("무효 처리됨", color = c.bad, container = c.badSoft) }
        return
    }
    val current = state.step()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        STEPS.forEachIndexed { i, label ->
            val done = current != null && i < current
            val now = i == current
            if (i > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(if (compact) 16.dp else 32.dp)
                        .height(2.dp)
                        .background(if (current != null && i <= current) c.ink2 else c.rule),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .then(
                        when {
                            now -> Modifier.background(c.primary, CircleShape)
                            done -> Modifier.background(c.ink2, CircleShape)
                            else -> Modifier.border(2.dp, c.rule, CircleShape)
                        },
                    ),
            ) {
                if (done) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = c.paper, modifier = Modifier.size(22.dp))
                } else {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (now) c.onPrimary else c.ink2,
                    )
                }
            }
            if (!compact || now) {
                Spacer(Modifier.width(10.dp))
                Text(
                    label,
                    style = if (now) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                    color = if (now) c.ink else c.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color, container: Color, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(container, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** '반납 기록을 남길까요?'의 답을 듣는 동안 자막 띠에 보여 주는 말 */
const val LISTENING_TEXT = "듣는 중입니다. 네 또는 아니요로 답해 주세요."

/**
 * 마지막 음성 안내를 자막으로 남긴다(TTS를 못 쓰거나 못 들었을 때). 말하는 동안에는 막대가 움직인다.
 * 답을 듣는 동안([listening])에는 그 사실을 주황으로 보여 주고, 오류가 있으면 같은 자리에 오류를 보여 준다.
 * 두 줄 자리를 늘 비워 둬 자막이 생기거나 사라져도 주 버튼이 늘 같은 높이에 있게 한다. 더 긴 자막은 위로 늘어난다.
 */
@Composable
fun CaptionBar(caption: String?, error: String?, speaking: Boolean, modifier: Modifier = Modifier, listening: Boolean = false) {
    val c = ChagokTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    val reserved = with(LocalDensity.current) { (style.lineHeight * 2).toDp() } + 32.dp
    val isError = error != null
    val isListening = !isError && listening
    val text = error ?: if (listening) LISTENING_TEXT else caption
    if (text == null) {
        Spacer(modifier.fillMaxWidth().height(reserved))
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = reserved)
            .background(
                when {
                    isError -> c.badSoft
                    isListening -> c.signalSoft
                    else -> c.sunken
                },
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).background(
                when {
                    isError -> c.bad
                    isListening -> c.signal
                    else -> c.primary
                },
                CircleShape,
            ),
        ) {
            when {
                isError -> Icon(painterResource(R.drawable.ic_error), contentDescription = "알림", tint = c.paper, modifier = Modifier.size(32.dp))
                isListening -> Icon(painterResource(R.drawable.ic_mic), contentDescription = "듣는 중", tint = c.onSignal, modifier = Modifier.size(32.dp))
                speaking -> VoiceBars(c.onPrimary, Modifier.size(28.dp))
                else -> Icon(painterResource(R.drawable.ic_voice), contentDescription = "음성 안내", tint = c.onPrimary, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(20.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "caption",
            modifier = Modifier.weight(1f),
        ) { shown ->
            Text(
                shown,
                style = style,
                color = if (isError) c.bad else c.ink,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoiceBars(color: Color, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "voice")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val w = size.width / 5
        repeat(3) { i ->
            val h = size.height * (0.3f + 0.7f * abs(sin((phase + i * 0.27f) * PI.toFloat())))
            drawRoundRect(color, Offset(w * 2 * i, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** QR 크기. 약 7.6cm이고, 시연 리포트(121×121모듈)면 모듈 하나가 5px이다. */
val QR_SIZE = 605.dp

/** QR 흰 판의 여백. 모듈 5px의 4칸(스캔에 필요한 여백)보다 넓다. */
private val QR_MARGIN = 24.dp

/** QR 흰 판의 폭. 넓은 창의 왼쪽 칸이 이 폭이다. */
val QR_BOARD = QR_SIZE + QR_MARGIN * 2

/**
 * 고객 폰으로 같은 기록을 넘기는 QR(기능 정의서 F-SHR-03). QR은 어두운 화면에서도 흰 판 위에 그린다.
 * 링크를 만들지 못했으면(기록이 너무 큼) QR 자리에 이유를 쓴다. [hash]를 주면 무결성 값을 함께 보여 준다.
 */
@Composable
fun QrPanel(url: String?, modifier: Modifier = Modifier, hash: String? = null, qrSize: Dp = QR_SIZE) {
    val c = ChagokTheme.colors
    Column(modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(28.dp))
                .then(if (c.dark) Modifier else Modifier.border(1.dp, c.rule, RoundedCornerShape(28.dp)))
                .padding(QR_MARGIN),
        ) {
            if (url != null) {
                QrCode(url, Modifier.size(qrSize))
            } else {
                // 흰 판 위라 테마와 상관없이 어두운 글자를 쓴다.
                Box(Modifier.size(qrSize), contentAlignment = Alignment.Center) {
                    Text(
                        "QR을 만들지 못했습니다.\n직원에게 알려 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF55616A),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_phone), contentDescription = null, tint = c.ink2, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("폰 카메라로 찍으면 같은 기록을 받습니다", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        }
        hash?.let {
            Spacer(Modifier.height(8.dp))
            IntegrityValue(it)
        }
    }
}

/** 무결성 값. 차 화면과 고객 폰이 같은 값을 보여 준다. */
@Composable
fun IntegrityValue(hash: String, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_verified), contentDescription = null, tint = c.ink2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("무결성 ", style = MaterialTheme.typography.labelMedium, color = c.ink2)
        Text(
            Canonical.short(hash),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = c.ink,
        )
    }
}

/**
 * 작은 잔량 막대. [before]가 있으면 인수 때 자리에 눈금을 두고, 줄어든 만큼을 주황으로 칠한다.
 */
@Composable
fun EnergyBar(after: Int, modifier: Modifier = Modifier, before: Int? = null) {
    val c = ChagokTheme.colors
    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val track = 14.dp.toPx()
        val top = (size.height - track) / 2
        val radius = CornerRadius(track / 2)
        val w = size.width
        fun at(percent: Int) = w * percent.coerceIn(0, 100) / 100f
        drawRoundRect(c.sunken, Offset(0f, top), Size(w, track), radius)
        drawRoundRect(c.ink2, Offset(0f, top), Size(at(after), track), radius)
        if (before != null && before > after) {
            drawRect(c.signal, Offset(at(after), top), Size(at(before) - at(after), track))
            val tick = 3.dp.toPx()
            drawRoundRect(c.ink, Offset(at(before) - tick / 2, 0f), Size(tick, size.height), CornerRadius(tick / 2))
        }
    }
}

// 잔량이 바뀌는 시간. 차를 읽는 간격(리스너가 없으면 2초)을 넘지 않게 잡아 값이 올 때마다 끊기지 않고 이어진다.
private const val GAUGE_MILLIS = 1_200

/**
 * 잔량 게이지(V-04 '반납하러 가기', V-05 반납 체크). 막대는 값이 바뀔 때만 움직이고, 모자란 양과 금액도 막대와 같은 값으로 함께 줄어든다.
 * 인수 눈금에 닿으면 금액이 사라지고 [reachedText]로 바뀐다. 색만으로 구분하지 않게 체크 아이콘과 글자를 함께 쓴다.
 * [headline]을 주면(도착 예상처럼 지금 값이 아닐 때) 위 줄의 이름과 숫자 대신 그 문장을 쓴다.
 */
@Composable
fun EnergyGaugeView(
    gauge: EnergyGauge,
    modifier: Modifier = Modifier,
    headline: String? = null,
    reachedText: String = EnergyGauge.REACHED,
) {
    val c = ChagokTheme.colors
    val type = MaterialTheme.typography
    val shown by animateFloatAsState(gauge.now.toFloat(), tween(GAUGE_MILLIS, easing = FastOutSlowInEasing), label = "energy")
    val g = gauge.at(shown.toDouble())
    val fill by animateColorAsState(if (g.reached) c.good else c.ink2, tween(360), label = "energyFill")
    // 찼다는 글로 바뀌는 동안 나가는 쪽 글자가 먼저 비지 않게 마지막 값을 들고 있는다.
    var lastShort by remember { mutableStateOf(g) }
    if (!g.reached) lastShort = g
    val reserved = with(LocalDensity.current) { type.titleMedium.lineHeight.toDp() + type.bodySmall.lineHeight.toDp() }
    val target = gauge.pickupPercent

    Column(modifier.semantics { contentDescription = "${gauge.item.label} 지금 ${gauge.nowPercent}%, 인수 때 $target%" }) {
        GaugeHead(
            tickFraction = target / 100f,
            left = {
                if (headline != null) {
                    Text(headline, style = type.titleLarge.tabular, color = c.ink)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(gauge.item.icon()), contentDescription = null, tint = c.ink2, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(gauge.item.label, style = type.titleMedium, color = c.ink2)
                        Spacer(Modifier.width(14.dp))
                        Text("${shown.roundToInt()}%", style = type.headlineMedium.tabular, color = c.ink)
                    }
                }
            },
            tick = { Text("인수 때 $target%", style = type.labelMedium.tabular, color = c.ink2) },
        )
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val track = 26.dp.toPx()
            val top = (size.height - track) / 2
            val radius = CornerRadius(track / 2)
            fun at(percent: Float) = size.width * percent.coerceIn(0f, 100f) / 100f
            drawRoundRect(c.sunken, Offset(0f, top), Size(size.width, track), radius)
            if (!g.reached) drawRoundRect(c.signal, Offset(0f, top), Size(at(target.toFloat()), track), radius)
            drawRoundRect(fill, Offset(0f, top), Size(max(at(shown), track), track), radius)
            val mark = 4.dp.toPx()
            drawRoundRect(c.ink, Offset(at(target.toFloat()) - mark / 2, 0f), Size(mark, size.height), CornerRadius(mark / 2))
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = g.reached,
            transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "energyText",
            modifier = Modifier.fillMaxWidth().heightIn(min = reserved),
        ) { reached ->
            if (reached) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = c.good, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(reachedText, style = type.titleLarge, color = c.good)
                }
            } else {
                Column {
                    Text(lastShort.refillText.orEmpty(), style = type.titleMedium.tabular, color = c.ink)
                    lastShort.settlementText?.let { Text(it, style = type.bodySmall.tabular, color = c.ink2) }
                }
            }
        }
    }
}

// 왼쪽 글과 눈금 위 글. 눈금 글은 눈금 가운데에 맞추되 왼쪽 글과 겹치면 오른쪽으로 밀고, 그래도 자리가 없으면 다음 줄로 내린다.
@Composable
private fun GaugeHead(tickFraction: Float, left: @Composable () -> Unit, tick: @Composable () -> Unit) {
    Layout(content = { left(); tick() }) { measurables, constraints ->
        val open = constraints.copy(minWidth = 0, minHeight = 0)
        val l = measurables[0].measure(open)
        val t = measurables[1].measure(open)
        val width = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val centered = (width * tickFraction).roundToInt() - t.width / 2
        val x = centered.coerceAtMost(width - t.width).coerceAtLeast(l.width + gap)
        val fits = x + t.width <= width
        val height = if (fits) max(l.height, t.height) else l.height + t.height
        layout(width, height) {
            l.place(0, if (fits) height - l.height else 0)
            if (fits) t.place(x, height - t.height) else t.place(0, l.height)
        }
    }
}

/** 아래로 더 읽을 것이 있으면 아래 끝을 흐리게 해 이어진다는 것을 알린다. 스크롤 수정자보다 앞에 둔다. */
fun Modifier.fadeBottom(canScrollForward: () -> Boolean, height: Dp = 32.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (canScrollForward()) {
            val h = height.toPx().coerceAtMost(size.height)
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstIn,
            )
        }
    }

fun Modifier.fadeBottom(state: ScrollState): Modifier = fadeBottom({ state.canScrollForward })

/** 둥근 판 위의 아이콘. 목록 줄 앞에 둔다. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, container: Color, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).background(container, CircleShape)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, count: Int? = null, color: Color = ChagokTheme.colors.ink2, @DrawableRes icon: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
        if (count != null) Text("$count", style = MaterialTheme.typography.titleSmall.tabular, color = color)
    }
}

@DrawableRes
internal fun StateItem.icon(): Int = when (this) {
    StateItem.FUEL, StateItem.TRIP_SINCE_REFILL -> R.drawable.ic_fuel
    StateItem.EV_SOC, StateItem.EV_CHARGING, StateItem.EV_CAPACITY -> R.drawable.ic_battery
    StateItem.CHARGE_PORT -> R.drawable.ic_charge_port
    StateItem.ODOMETER -> R.drawable.ic_odometer
    StateItem.DOORS, StateItem.DOOR_LOCKS -> R.drawable.ic_door
    StateItem.WINDOWS -> R.drawable.ic_window
    StateItem.HEADLIGHTS -> R.drawable.ic_headlamp
    StateItem.TAIL_LAMP -> R.drawable.ic_position_lamp
    StateItem.CABIN_LIGHT -> R.drawable.ic_cabin_light
    StateItem.SEATS -> R.drawable.ic_seat
    StateItem.ENGINE_OIL -> R.drawable.ic_oil
    StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID -> R.drawable.ic_fluid
    StateItem.TELLTALES -> R.drawable.ic_warning
    StateItem.TIRES -> R.drawable.ic_tire
    StateItem.TRUNK -> R.drawable.ic_trunk
    StateItem.PARKING_BRAKE, StateItem.AIRBAGS -> R.drawable.ic_car
}

@DrawableRes
internal fun CheckItem.icon(): Int = when (this) {
    is CheckItem.WindowOpen -> R.drawable.ic_window
    is CheckItem.DoorOpen -> R.drawable.ic_door
    is CheckItem.ClosureOpen -> item.icon()
    is CheckItem.LightOn -> item.icon()
    is CheckItem.Unreadable -> item.icon()
    is CheckItem.SeatDetected -> R.drawable.ic_seat
    is CheckItem.EnergyDrop -> item.icon()
    is CheckItem.NewTelltales, is CheckItem.TelltalesUnverified -> R.drawable.ic_warning
}
