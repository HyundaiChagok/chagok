package app.chagok.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.Voice
import app.chagok.core.rental.lostReading
import app.chagok.rental.RentalUi

/**
 * V-05 반납 체크. 입력 없이 차 상태만으로 화면이 따라온다. 고객이 창문을 닫으면 차 그림의 그 부위가 꺼지고, 줄이 체크로 바뀌고, 숫자가 줄고, 음성 안내가 나온다.
 * 고친 줄은 이 체크가 끝날 때까지 제자리에 체크로 남아 무엇을 고쳤는지 보인다. 다시 열면 줄이 다시 켜진다.
 *
 * 고칠 것이 없어도 '모두 확인됨'이라고 하지 않는다. 리포트가 '확인 필요'로 적을 잔량 차이와 경고등은 리포트에 남는다고 말한다.
 *
 * 왼쪽 아래 버튼은 첫 판정 전에는 누를 수 없고, 남은 것이 있으면 보조 모양의 'N개 남은 채로 남기기', 모두 끝나면 주 버튼 '반납 기록 남기기'다.
 * 잔량은 게이지로 크게 보여 주고, 인수 때보다 모자라면 '나중에'가 '충전하고 다시 하기'가 된다.
 */
@Composable
internal fun ReturnCheckView(ui: RentalUi, onLater: () -> Unit, onConfirm: () -> Unit) {
    val ready = ui.checkReady
    val current = ui.check.filter { it.actionable }
    val info = ui.check.filterNot { it.actionable || it is CheckItem.EnergyDrop }
    // 고친 항목도 제자리에 남겨 체크를 보여 준다. 읽지 못하게 돼 사라진 줄은 고친 것이 아니므로 뺀다.
    var seen by remember { mutableStateOf(current) }
    LaunchedEffect(current) { seen = seen + current.filter { it !in seen } }
    val rows = (seen + current.filter { it !in seen })
        .filterNot { it !in current && it.lostReading(current) }
        .map { it to (it !in current) }
    val lit = current.mapNotNull { it.mapPart() }.toSet()
    val description = if (current.isEmpty()) "차 그림: 확인할 곳 없음" else "차 그림: ${current.joinToString { it.title }}"
    val left = current.size
    val gauge = ui.energy.takeIf { ready }

    WithMap(
        map = { VehicleMap(lit, Modifier.fillMaxSize(), scanning = !ready, description = description) },
        lead = { CheckHeading(left, ready, hadItems = rows.isNotEmpty(), reportNote = Voice.reportNote(ui.check)) },
        actions = {
            ActionRow {
                ChagokButton(
                    if (ready && left > 0) "${left}개 남은 채로 남기기" else "반납 기록 남기기",
                    onConfirm,
                    kind = if (ready && left > 0) ButtonKind.Secondary else ButtonKind.Primary,
                    enabled = ready,
                    modifier = Modifier.widthIn(min = 420.dp),
                )
                ChagokButton(gauge?.takeIf { !it.reached }?.retryLabel ?: "나중에", onLater, kind = ButtonKind.Secondary)
            }
        },
    ) {
        Spacer(Modifier.height(if (LocalWide.current) 16.dp else 0.dp))
        val list = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().fadeBottom({ list.canScrollForward }), state = list) {
            if (ready) {
                items(rows, key = { it.first.toString() }) { (item, done) -> CheckRow(item, done, Modifier.animateItem()) }
                if (info.isNotEmpty()) {
                    item(key = "info") {
                        SectionTitle("반납 기록에 함께 남는 것", Modifier.animateItem().padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(info, key = { "info-$it" }) { InfoRow(it, Modifier.animateItem()) }
                }
            }
        }
        gauge?.let {
            Spacer(Modifier.height(16.dp))
            EnergyGaugeView(it)
        }
    }
}

// 머리글이 세 상태를 말한다: 읽는 중, 남은 것(숫자), 모두 확인됨(체크). 상태가 바뀌는 순간이 한눈에 보이도록 제자리에서 갈아 끼운다.
@Composable
private fun CheckHeading(count: Int, ready: Boolean, hadItems: Boolean, reportNote: String?) {
    val c = ChagokTheme.colors
    val phase = when {
        !ready -> HeadingPhase.READING
        count > 0 -> HeadingPhase.LEFT
        else -> HeadingPhase.CLEAR
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        label = "checkHeading",
    ) { shown ->
        when (shown) {
            HeadingPhase.READING -> Column {
                Text("차 상태를 확인하는 중입니다", style = MaterialTheme.typography.headlineLarge, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Text("문·창문·트렁크·충전구·라이트·좌석·잔량·경고등", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            HeadingPhase.LEFT -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("확인할 것", style = MaterialTheme.typography.headlineLarge, color = c.ink)
                    Spacer(Modifier.width(20.dp))
                    CountBadge(count)
                }
                Spacer(Modifier.height(4.dp))
                Text("닫거나 끄면 차가 바로 확인합니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            }
            HeadingPhase.CLEAR -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(R.drawable.ic_check, c.onPrimary, c.good, size = 80.dp)
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(if (hadItems) Voice.ALL_FIXED else Voice.NOTHING_TO_FIX, style = MaterialTheme.typography.headlineLarge, color = c.ink)
                    Text(reportNote ?: "반납 기록을 남길 수 있습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                }
            }
        }
    }
}

private enum class HeadingPhase { READING, LEFT, CLEAR }

// 숫자가 바뀌는 것이 보이도록 위로 밀어 올린다. 값이 바뀔 때만 움직인다.
@Composable
private fun CountBadge(count: Int) {
    val c = ChagokTheme.colors
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).background(c.signal, CircleShape)) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith (slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(120)))
            },
            label = "count",
        ) { shown ->
            Text("$shown", style = MaterialTheme.typography.headlineMedium.tabular, color = c.onSignal)
        }
    }
}

private val CheckItem.title: String
    get() = when (this) {
        is CheckItem.SeatDetected -> "${area.label} 좌석"
        else -> subject
    }

private val CheckItem.status: String
    get() = when (this) {
        is CheckItem.WindowOpen, is CheckItem.DoorOpen, is CheckItem.ClosureOpen -> "열림"
        is CheckItem.LightOn -> "켜짐"
        is CheckItem.Unreadable -> "읽지 못함"
        is CheckItem.SeatDetected -> "감지"
        is CheckItem.EnergyDrop -> "${before}% → ${after}%"
        is CheckItem.NewTelltales -> "새로 켜짐 ${telltales.size}"
        is CheckItem.TelltalesUnverified -> "켜짐 ${telltales.size}"
    }

// 고친 뒤의 상태. 무엇이 어떻게 됐는지만 적는다.
private val CheckItem.doneStatus: String
    get() = when (this) {
        is CheckItem.WindowOpen, is CheckItem.DoorOpen, is CheckItem.ClosureOpen -> "닫힘"
        is CheckItem.LightOn -> "꺼짐"
        is CheckItem.SeatDetected -> "비어 있음"
        else -> "다시 읽음"
    }

// 머리글이 '닫거나 끄면 확인합니다'를 한 번 말하므로, 지시가 다른 줄만 보조 문구를 둔다.
private val CheckItem.hint: String?
    get() = when (this) {
        is CheckItem.Unreadable -> "직접 확인해 주세요"
        is CheckItem.SeatDetected -> "두고 내리는 물건이 없는지 확인해 주세요"
        else -> null
    }

/** 한 줄. 무엇이 어떤 상태인지만 보여 준다. 읽지 못한 항목은 고칠 것이 아니므로 주황이 아니라 회색으로 그린다. */
@Composable
private fun CheckRow(item: CheckItem, done: Boolean, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    val unreadable = item is CheckItem.Unreadable
    val statusColor by animateColorAsState(
        when {
            done -> c.good
            unreadable -> c.ink2
            else -> c.signalInk
        },
        tween(240),
        label = "rowStatus",
    )
    Column(modifier) {
        // 고친 줄은 조금 낮춘다. 다섯 줄을 고쳐도 아래 '반납 기록에 함께 남는 것'이 게이지 뒤로 밀려나 제목만 남지 않게 한다.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = if (done) 72.dp else 92.dp).padding(vertical = if (done) 6.dp else 10.dp)) {
            Crossfade(done, animationSpec = tween(240), label = "badge") { isDone ->
                when {
                    isDone -> IconBadge(R.drawable.ic_check, c.good, c.goodSoft, size = 60.dp)
                    unreadable -> IconBadge(item.icon(), c.ink2, c.sunken, size = 60.dp)
                    else -> IconBadge(item.icon(), c.signalInk, c.signalSoft, size = 60.dp)
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = if (done) c.ink2 else c.ink)
                item.hint?.takeIf { !done }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2) }
            }
            Spacer(Modifier.width(16.dp))
            Text(if (done) item.doneStatus else item.status, style = MaterialTheme.typography.titleLarge, color = statusColor)
        }
        HorizontalDivider(color = c.rule)
    }
}

/**
 * 고칠 것은 아니지만 기록에 남는 경고등. 새로 켜진 것은 지금 볼 것이라 주황으로, 인수 때 확인하지 못해 새로 켜졌는지 모르는 것은 회색으로 나눈다.
 * '새로 켜짐'으로 말하면 고객에게 불리할 수 있기 때문이다.
 */
@Composable
private fun InfoRow(item: CheckItem, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    val isNew = item is CheckItem.NewTelltales
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Start) {
        IconBadge(item.icon(), if (isNew) c.signalInk else c.ink2, if (isNew) c.signalSoft else c.sunken, size = 60.dp)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            when (item) {
                is CheckItem.NewTelltales -> {
                    Text("새로 켜진 경고등 ${item.telltales.size}개", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(item.telltales.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
                is CheckItem.TelltalesUnverified -> {
                    Text("켜진 경고등 ${item.telltales.size}개 · 인수 때 확인 못 함", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(item.telltales.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
                else -> Text(item.detail, style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
        }
    }
}
