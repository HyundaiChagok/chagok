package app.chagok.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chagok.R
import app.chagok.core.inspect.Comparison
import app.chagok.core.inspect.ComparisonRow
import app.chagok.core.inspect.Verdict
import app.chagok.core.inspect.describe
import app.chagok.core.inspect.display
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.RentalReport
import app.chagok.core.rental.RentalState
import app.chagok.demo.DemoVehicle
import app.chagok.rental.RentalController
import app.chagok.rental.RentalUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 차곡의 한 화면. 상태마다 기능 정의서 05절의 화면(V-01~V-07)을 그린다. '차곡' 글자를 길게 누르면 직원 화면이 열린다.
 * 넓은 창(가로 1200dp 이상)은 왼쪽에 차 그림, 오른쪽에 내용을 두고, 좁은 창(앱 두 개를 띄운 세로 창)은 위아래로 쌓는다.
 */
@Composable
fun ChagokScreen(
    controller: RentalController,
    demo: DemoVehicle,
    vehicleId: String,
    sourceLabel: String,
    fakeOnly: Boolean,
    speaking: StateFlow<Boolean>,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val talking by speaking.collectAsStateWithLifecycle()
    var staffOpen by remember { mutableStateOf(false) }
    var pickupSeen by remember(ui.pickup?.hash) { mutableStateOf(false) }
    val c = ChagokTheme.colors

    BoxWithConstraints(Modifier.fillMaxSize().background(c.paper)) {
        val wide = maxWidth >= 1200.dp
        CompositionLocalProvider(LocalWide provides wide) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 32.dp, vertical = 20.dp)) {
                AppHeader(ui.state, ui.demo, sourceLabel, onStaff = { staffOpen = true })
                val current = Shown(ui, pickupSeen)
                // 자막은 그 말을 한 화면에서만 보인다. 인수 때의 'QR을 받으세요'가 대여 중 화면에 남지 않게 한다.
                val captionScreen = remember(ui.caption) { current.screen }
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.screen },
                    transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                    label = "screen",
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
                ) { shown ->
                    val s = shown.ui
                    when (shown.screen) {
                        Screen.IDLE -> Idle(vehicleId, sourceLabel, s.parked, controller::startPickup)
                        Screen.NOTICE -> Notice(controller::acceptNotice, controller::declineNotice)
                        Screen.READING_PICKUP -> Reading("차량 상태를 읽는 중입니다", "잔량·경고등·소모품·문·창문·라이트")
                        Screen.PICKUP -> s.pickup?.let { PickupDone(it, s.pickupUrl, onDone = { pickupSeen = true }) }
                        Screen.RENTAL -> InRental(s.pickup, s.parked, controller::startReturnCheck, onShowPickup = { pickupSeen = false })
                        Screen.CHECK -> ReturnCheckView(s.check, s.checkReady, controller::cancelReturnCheck, controller::confirmReturn)
                        Screen.READING_RETURN -> Reading("반납 기록을 남기는 중입니다", "인수 때와 지금을 항목별로 비교합니다")
                        Screen.REPORT -> s.report?.let { ReportView(it, s.reportUrl, controller::close) }
                        Screen.VOIDED -> Voided(controller::close)
                    }
                }
                CaptionBar(ui.caption.takeIf { captionScreen == current.screen }, ui.error, talking)
            }
        }
    }

    if (staffOpen) {
        StaffPanel(ui, vehicleId, sourceLabel, demo, fakeOnly, onVoid = controller::void, onDismiss = { staffOpen = false })
    }
}

private enum class Screen { IDLE, NOTICE, READING_PICKUP, PICKUP, RENTAL, CHECK, READING_RETURN, REPORT, VOIDED }

/** 화면 전환은 [screen]이 바뀔 때만 일어난다. 같은 화면 안의 값 변화(반납 체크 목록 등)는 제자리에서 바뀐다. */
private data class Shown(val ui: RentalUi, val pickupSeen: Boolean) {
    val screen: Screen
        get() = when (ui.state) {
            RentalState.IDLE -> Screen.IDLE
            RentalState.NOTICE -> Screen.NOTICE
            RentalState.PICKUP -> Screen.READING_PICKUP
            RentalState.IN_RENTAL -> if (ui.pickup != null && !pickupSeen) Screen.PICKUP else Screen.RENTAL
            RentalState.RETURN_CHECK -> Screen.CHECK
            RentalState.RETURN -> Screen.READING_RETURN
            RentalState.RETURNED -> Screen.REPORT
            RentalState.VOIDED -> Screen.VOIDED
        }
}

/** V-01 대기. 차량 번호와 연결 상태를 함께 보여 준다. */
@Composable
private fun Idle(vehicleId: String, sourceLabel: String, parked: Boolean, onStart: () -> Unit) {
    val c = ChagokTheme.colors
    WithMap(map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), description = "차 그림") }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("렌터카 인수·반납 기록", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text("외관은 사진이, 기능은 차가 기록합니다.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
            Spacer(Modifier.height(40.dp))
            FlowRowTags {
                Tag("차량 $vehicleId", color = c.ink, container = c.sunken, icon = R.drawable.ic_car)
                val connected = sourceLabel.startsWith("Pleos")
                Tag(
                    if (connected) "$sourceLabel 연결됨" else sourceLabel,
                    color = if (connected) c.good else c.ink2,
                    container = if (connected) c.goodSoft else c.sunken,
                    icon = if (connected) R.drawable.ic_check_circle else R.drawable.ic_info,
                )
            }
            Spacer(Modifier.height(56.dp))
            ChagokButton("인수 기록 시작", onStart, enabled = parked, modifier = Modifier.widthIn(min = 400.dp))
            if (!parked) ParkFirst("정차하면 시작할 수 있습니다")
        }
    }
}

/** V-02 고지. 문구는 법률 검토 전 초안이다(요구사항 SER-03). */
@Composable
private fun Notice(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = ChagokTheme.colors
    val recorded = listOf(
        R.drawable.ic_fuel to "연료·배터리 잔량, 주행거리",
        R.drawable.ic_warning to "경고등, 엔진오일·브레이크액·워셔액",
        R.drawable.ic_tire to "타이어 저압, 에어백",
        R.drawable.ic_door to "문·창문·라이트",
    )
    val notRecorded = listOf(
        R.drawable.ic_location_off to "위치",
        R.drawable.ic_person_off to "이름·연락처",
        R.drawable.ic_mic_off to "음성 원문",
        R.drawable.ic_history_off to "운전 습관, 문 열림 이력",
    )
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("차량 상태를 기록할까요?", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text(
                "기록은 인수·반납 상태 확인에만 쓰고 180일 보관합니다. 고객도 화면의 QR로 같은 기록을 받습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.ink2,
            )
            Spacer(Modifier.height(48.dp))
            if (LocalWide.current) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    Promise("기록하는 것", R.drawable.ic_check_circle, recorded, Modifier.weight(1f))
                    Promise("기록하지 않는 것", R.drawable.ic_block, notRecorded, Modifier.weight(1f))
                }
            } else {
                Promise("기록하는 것", R.drawable.ic_check_circle, recorded)
                Spacer(Modifier.height(40.dp))
                Promise("기록하지 않는 것", R.drawable.ic_block, notRecorded)
            }
        }
        Spacer(Modifier.height(24.dp))
        ActionRow {
            ChagokButton("기록하지 않음", onDecline, kind = ButtonKind.Secondary)
            ChagokButton("확인하고 기록 시작", onAccept)
        }
    }
}

@Composable
private fun Promise(title: String, icon: Int, items: List<Pair<Int, String>>, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = c.ink, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = c.rule)
        items.forEach { (itemIcon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                Icon(painterResource(itemIcon), contentDescription = null, tint = c.ink2, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(20.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
            HorizontalDivider(color = c.rule)
        }
    }
}

/** 상태를 읽는 동안. 0.25초를 넘기는 응답은 진행 중임을 보여 준다(Pleos 디자인 가이드). */
@Composable
private fun Reading(title: String, detail: String) {
    val c = ChagokTheme.colors
    WithMap(map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), scanning = true, description = "차 상태를 읽는 중") }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        }
    }
}

/** V-03 인수 기록. 잔량·주행거리·경고등 요약, 기록 번호, 무결성 값, QR */
@Composable
private fun PickupDone(pickup: SnapshotRecord, url: String?, onDone: () -> Unit) {
    val heading = @Composable { RecordHeading("인수 기록을 남겼습니다", "${time(pickup.capturedAt)}  ·  기록 번호 ${pickup.recordNo}") }
    val scroll = rememberScrollState()
    if (LocalWide.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().fadeBottom(scroll).verticalScroll(scroll)) {
                heading()
                Spacer(Modifier.height(48.dp))
                PickupFacts(pickup.state, columns = 3)
            }
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrPanel(url, pickup.hash, qrSize = 380.dp)
                Spacer(Modifier.weight(1f))
                ChagokButton("확인", onDone, modifier = Modifier.align(Alignment.End).widthIn(min = 280.dp))
            }
        }
    } else {
        // 좁은 창에서는 고객이 찍을 QR을 먼저 둔다.
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fadeBottom(scroll).verticalScroll(scroll)) {
                heading()
                Spacer(Modifier.height(36.dp))
                QrPanel(url, pickup.hash, Modifier.align(Alignment.CenterHorizontally), qrSize = 360.dp)
                Spacer(Modifier.height(40.dp))
                PickupFacts(pickup.state, columns = 2)
            }
            Spacer(Modifier.height(24.dp))
            ActionRow { ChagokButton("확인", onDone, modifier = Modifier.widthIn(min = 280.dp)) }
        }
    }
}

private val PICKUP_FACTS = listOf(
    StateItem.FUEL, StateItem.EV_SOC, StateItem.ODOMETER, StateItem.TELLTALES, StateItem.TIRES,
    StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
)

@Composable
private fun PickupFacts(state: VehicleState, columns: Int) {
    // 휘발유차의 배터리처럼 이 차에 없는 항목은 칸을 만들지 않는다.
    val items = PICKUP_FACTS.filter { state.display(it) != null || state.missing(it) !is Unavailable.Unsupported }
    Column {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                row.forEach { item -> Fact(state, item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 인수 기록의 한 칸. 켜진 경고등·부족한 소모품·저압 타이어는 주황으로 적는다(고객이 인수 때부터였다는 근거). */
@Composable
private fun Fact(state: VehicleState, item: StateItem, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    val value = state.display(item)
    val attention = when (item) {
        StateItem.TELLTALES -> state.telltales?.isNotEmpty() == true
        StateItem.TIRES -> state.tires.values.any { it.lowPressure == true }
        StateItem.ENGINE_OIL -> state.engineOil == FluidLevel.LOW
        StateItem.BRAKE_FLUID -> state.brakeFluid == FluidLevel.LOW
        StateItem.WASHER_FLUID -> state.washerFluid == FluidLevel.LOW
        else -> false
    }
    val percent = when (item) {
        StateItem.FUEL -> state.fuelPercent
        StateItem.EV_SOC -> state.evSocPercent
        else -> null
    }
    // 칸마다 위에 선을 둬 표처럼 읽히게 한다.
    Column(modifier.padding(bottom = 32.dp)) {
        HorizontalDivider(color = if (attention) c.signal else c.rule, thickness = if (attention) 2.dp else 1.dp)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(item.icon()),
                contentDescription = null,
                tint = if (attention) c.signalInk else c.ink2,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = c.ink2)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            value ?: state.missing(item)?.describe().orEmpty(),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium).tabular,
            color = when {
                value == null -> c.ink2
                attention -> c.signalInk
                else -> c.ink
            },
        )
        if (percent != null) {
            Spacer(Modifier.height(10.dp))
            EnergyBar(after = percent.toInt())
        }
    }
}

/** V-04 대여 중. '반납 체크'는 정차 중에만 보인다. 주행 중에는 아무것도 묻지 않는다. */
@Composable
private fun InRental(pickup: SnapshotRecord?, parked: Boolean, onReturnCheck: () -> Unit, onShowPickup: () -> Unit) {
    val c = ChagokTheme.colors
    WithMap(map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), description = "차 그림") }) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                Tag("기록 중", color = c.good, container = c.goodSoft, icon = R.drawable.ic_check_circle)
                Spacer(Modifier.height(24.dp))
                Text("대여 중", style = MaterialTheme.typography.headlineLarge, color = c.ink)
                pickup?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("인수 ${time(it.capturedAt)}  ·  기록 번호 ${it.recordNo}", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                }
                Spacer(Modifier.height(40.dp))
                Note(R.drawable.ic_lock, "위치와 운전 습관은 기록하지 않습니다.")
                Spacer(Modifier.height(16.dp))
                Note(R.drawable.ic_info, "반납 지점에 도착해 정차하면 반납 체크를 눌러 주세요.")
            }
            Spacer(Modifier.height(24.dp))
            ActionRow {
                if (pickup != null) ChagokButton("인수 기록 보기", onShowPickup, kind = ButtonKind.Secondary)
                if (parked) ChagokButton("반납 체크", onReturnCheck, modifier = Modifier.widthIn(min = 320.dp))
                else ParkFirst("정차하면 반납 체크를 할 수 있습니다")
            }
        }
    }
}

/**
 * V-05 반납 체크. 차 그림에서 열린 곳·켜진 곳을 주황으로 켜고, 고객이 고치면 그 줄이 '확인됨'으로 바뀐 뒤 사라진다.
 * 잔량·새 경고등은 고칠 것이 아니라 기록에 남는다는 사실만 알린다.
 */
@Composable
private fun ReturnCheckView(checkItems: List<CheckItem>, ready: Boolean, onLater: () -> Unit, onConfirm: () -> Unit) {
    val c = ChagokTheme.colors
    val current = checkItems.filter { it.actionable }
    val info = checkItems.filterNot { it.actionable }
    // 고친 항목도 잠시 제자리에 남겨 '확인됨'을 보여 준다.
    var order by remember { mutableStateOf(current) }
    val latest by rememberUpdatedState(current)
    LaunchedEffect(current) { order = order + current.filter { it !in order } }
    val rows = order.map { it to (it !in current) } + current.filter { it !in order }.map { it to false }
    val lit = current.mapNotNull { it.mapPart() }.toSet()
    val description = if (current.isEmpty()) "차 그림: 확인할 곳 없음" else "차 그림: ${current.joinToString { it.title }}"

    WithMap(map = { VehicleMap(lit, Modifier.fillMaxSize(), scanning = !ready, description = description) }) {
        Column(Modifier.fillMaxSize()) {
            CheckHeading(current.size, ready)
            Spacer(Modifier.height(24.dp))
            val list = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fadeBottom({ list.canScrollForward }), state = list) {
                if (ready) {
                    items(rows, key = { it.first.toString() }) { (item, done) ->
                        CheckRow(item, done, Modifier.animateItem())
                        if (done) {
                            LaunchedEffect(Unit) {
                                delay(2_400)
                                if (item !in latest) order = order - item
                            }
                        }
                    }
                    if (info.isNotEmpty()) {
                        item(key = "info") {
                            SectionTitle("반납 기록에 함께 남는 것", Modifier.animateItem().padding(top = 32.dp, bottom = 4.dp))
                        }
                        items(info, key = { "info-$it" }) { InfoRow(it, Modifier.animateItem()) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            ActionRow {
                ChagokButton("나중에", onLater, kind = ButtonKind.Secondary)
                ChagokButton("반납 기록 남기기", onConfirm, modifier = Modifier.widthIn(min = 360.dp))
            }
        }
    }
}

@Composable
private fun CheckHeading(count: Int, ready: Boolean) {
    val c = ChagokTheme.colors
    when {
        !ready -> {
            Text("차 상태를 확인하는 중입니다", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text("문·창문·라이트·좌석·잔량·경고등", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        }
        count == 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_check, c.good, c.goodSoft, size = 80.dp)
            Spacer(Modifier.width(24.dp))
            Column {
                Text("확인할 것이 없습니다", style = MaterialTheme.typography.headlineLarge, color = c.ink)
                Text("반납 기록을 남길 수 있습니다.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
            }
        }
        else -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("확인할 것", style = MaterialTheme.typography.headlineLarge, color = c.ink)
                Spacer(Modifier.width(20.dp))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).background(c.signal, CircleShape)) {
                    Text("$count", style = MaterialTheme.typography.headlineMedium.tabular, color = c.onSignal)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("고치는 즉시 다시 확인합니다.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
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
        is CheckItem.WindowOpen, is CheckItem.DoorOpen -> "열림"
        is CheckItem.LightOn -> "켜짐"
        is CheckItem.SeatDetected -> "감지"
        is CheckItem.EnergyDrop -> "${before}% → ${after}%"
        is CheckItem.NewTelltales -> "새로 켜짐 ${telltales.size}"
    }

private val CheckItem.hint: String?
    get() = when (this) {
        is CheckItem.WindowOpen, is CheckItem.DoorOpen -> "닫으면 자동으로 확인합니다"
        is CheckItem.LightOn -> "끄면 자동으로 확인합니다"
        is CheckItem.SeatDetected -> "두고 내리는 물건이 없는지 확인해 주세요"
        else -> null
    }

@Composable
private fun CheckRow(item: CheckItem, done: Boolean, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 124.dp).padding(vertical = 16.dp)) {
            Crossfade(done, animationSpec = tween(240), label = "badge") { isDone ->
                if (isDone) IconBadge(R.drawable.ic_check, c.good, c.goodSoft)
                else IconBadge(item.icon(), c.signalInk, c.signalSoft)
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge, color = if (done) c.ink2 else c.ink)
                AnimatedVisibility(visible = !done && item.hint != null, exit = fadeOut(tween(160)) + shrinkVertically(tween(240))) {
                    Text(item.hint.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                }
            }
            Spacer(Modifier.width(16.dp))
            Crossfade(done, animationSpec = tween(240), label = "status") { isDone ->
                Text(
                    if (isDone) "확인됨" else item.status,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDone) c.good else c.signalInk,
                )
            }
        }
        HorizontalDivider(color = c.rule)
    }
}

@Composable
private fun InfoRow(item: CheckItem, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        IconBadge(item.icon(), c.ink2, c.sunken, size = 56.dp)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            when (item) {
                is CheckItem.EnergyDrop -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.item.label, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Text("인수 ${item.before}%  →  지금 ${item.after}%", style = MaterialTheme.typography.titleMedium.tabular, color = c.ink)
                    }
                    Spacer(Modifier.height(6.dp))
                    EnergyBar(after = item.after, before = item.before)
                }
                is CheckItem.NewTelltales -> {
                    Text("새로 켜진 경고등 ${item.telltales.size}개", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(item.telltales.joinToString { it.label }, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                }
                else -> Text(item.detail, style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
        }
    }
}

/** V-06 반납 리포트. 차 화면, 고객 폰, 운영자 웹이 같은 기록 번호와 무결성 값을 보여 준다. */
@Composable
private fun ReportView(report: RentalReport, url: String?, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    if (LocalWide.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            ReportDetails(report, Modifier.weight(1f).fillMaxHeight().fadeBottom(scroll).verticalScroll(scroll))
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrPanel(url, report.hash, qrSize = 380.dp)
                Spacer(Modifier.weight(1f))
                ChagokButton("닫기", onClose, modifier = Modifier.align(Alignment.End).widthIn(min = 280.dp))
            }
        }
    } else {
        // 좁은 창에서는 고객이 찍을 QR을 요약 바로 아래에 둔다.
        Column(Modifier.fillMaxSize()) {
            ReportDetails(report, Modifier.weight(1f).fadeBottom(scroll).verticalScroll(scroll)) {
                Spacer(Modifier.height(40.dp))
                QrPanel(url, report.hash, Modifier.fillMaxWidth(), qrSize = 360.dp)
            }
            Spacer(Modifier.height(24.dp))
            ActionRow { ChagokButton("닫기", onClose, modifier = Modifier.widthIn(min = 280.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportDetails(
    report: RentalReport,
    modifier: Modifier = Modifier,
    afterSummary: @Composable ColumnScope.() -> Unit = {},
) {
    val c = ChagokTheme.colors
    val comparison = report.comparison
    val rows = comparison.rows.groupBy { it.verdict }
    Column(modifier) {
        RecordHeading("반납 기록을 남겼습니다", "${span(report.pickup.capturedAt, report.returned.capturedAt)}  ·  기록 번호 ${report.recordNo}")
        Spacer(Modifier.height(40.dp))
        VerdictSummary(comparison)
        Spacer(Modifier.height(20.dp))
        Note(R.drawable.ic_info, RentalReport.DISCLAIMER)
        afterSummary()
        rows[Verdict.NEEDS_CHECK]?.let { group ->
            SectionTitle(Verdict.NEEDS_CHECK.label, Modifier.padding(top = 40.dp, bottom = 4.dp), group.size, c.signalInk)
            group.forEach { ReportRow(it) }
        }
        rows[Verdict.NOTE]?.let { group ->
            SectionTitle(Verdict.NOTE.label, Modifier.padding(top = 40.dp, bottom = 4.dp), group.size)
            group.forEach { ReportRow(it) }
        }
        rows[Verdict.UNCHANGED]?.let { group ->
            SectionTitle(Verdict.UNCHANGED.label, Modifier.padding(top = 40.dp, bottom = 16.dp), group.size)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                group.forEach { Tag(it.item.label, color = c.ink2, container = c.sunken, icon = it.item.icon()) }
            }
        }
        rows[Verdict.UNKNOWN]?.let { group ->
            SectionTitle(Verdict.UNKNOWN.label, Modifier.padding(top = 40.dp, bottom = 4.dp), group.size)
            group.forEach { ReportRow(it) }
        }
    }
}

// 꽉 찬 칸은 확인 필요·변화 없음, 테두리 칸은 참고(주황)·확인 불가(회색)다.
private fun Verdict.color(c: ChagokColors): Color = when (this) {
    Verdict.NEEDS_CHECK, Verdict.NOTE -> c.signal
    Verdict.UNCHANGED -> c.ink2.copy(alpha = 0.35f)
    Verdict.UNKNOWN -> c.ink3
}

private val Verdict.outlined: Boolean get() = this == Verdict.NOTE || this == Verdict.UNKNOWN

/** 분류별 개수. 막대 한 칸이 확인한 항목 하나다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictSummary(comparison: Comparison) {
    val c = ChagokTheme.colors
    val rows = comparison.rows
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        if (rows.isEmpty()) return@Canvas
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (rows.size - 1)) / rows.size
        val radius = CornerRadius(6.dp.toPx())
        rows.forEachIndexed { i, row ->
            val topLeft = Offset(i * (w + gap), 0f)
            if (row.verdict.outlined) {
                val inset = 1.5.dp.toPx()
                drawRoundRect(row.verdict.color(c), topLeft + Offset(inset, inset), Size(w - inset * 2, size.height - inset * 2), radius, style = Stroke(inset * 2))
            } else {
                drawRoundRect(row.verdict.color(c), topLeft, Size(w, size.height), radius)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Verdict.entries.forEach { verdict ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .then(
                            if (verdict.outlined) Modifier.border(3.dp, verdict.color(c), RoundedCornerShape(5.dp))
                            else Modifier.background(verdict.color(c), RoundedCornerShape(5.dp)),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(verdict.label, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                Spacer(Modifier.width(10.dp))
                Text("${comparison.count(verdict)}", style = MaterialTheme.typography.titleLarge.tabular, color = c.ink)
            }
        }
    }
}

/** 리포트 한 줄. 규칙이 쓴 문구가 항목 이름을 담고 있어 첫 문구를 제목으로 쓴다. 잔량은 막대를 곁들인다. */
@Composable
private fun ReportRow(row: ComparisonRow) {
    val c = ChagokTheme.colors
    val attention = row.verdict == Verdict.NEEDS_CHECK
    val lines = if (row.verdict == Verdict.UNKNOWN) listOf("${row.item.label}  ·  ${row.notes.joinToString()}") else row.notes
    val before = row.before?.removeSuffix("%")?.toIntOrNull()
    val after = row.after?.removeSuffix("%")?.toIntOrNull()
    val energy = row.item == StateItem.FUEL || row.item == StateItem.EV_SOC
    Column {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            IconBadge(
                row.item.icon(),
                tint = if (attention) c.signalInk else c.ink2,
                container = if (attention) c.signalSoft else c.sunken,
                size = 56.dp,
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                lines.firstOrNull()?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = if (row.verdict == Verdict.UNKNOWN) c.ink2 else c.ink)
                }
                lines.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink2) }
                if (energy && after != null) {
                    Spacer(Modifier.height(8.dp))
                    EnergyBar(after = after, before = before, modifier = Modifier.widthIn(max = 560.dp))
                }
            }
        }
        HorizontalDivider(color = c.rule)
    }
}

/** 무효. 리포트를 만들지 않는다. */
@Composable
private fun Voided(onClose: () -> Unit) {
    val c = ChagokTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(R.drawable.ic_block, c.ink2, c.sunken, size = 104.dp)
            Spacer(Modifier.height(32.dp))
            Text("무효 처리했습니다", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text("이 대여는 리포트를 만들지 않습니다.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        }
        ActionRow { ChagokButton("닫기", onClose, modifier = Modifier.widthIn(min = 280.dp)) }
    }
}

/** V-07 직원 설정. 차량 식별, 마지막 QR 다시 보기, 무효 처리, 시연 조작 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffPanel(
    ui: RentalUi,
    vehicleId: String,
    sourceLabel: String,
    demo: DemoVehicle,
    fakeOnly: Boolean,
    onVoid: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = ChagokTheme.colors
    var showQr by remember { mutableStateOf(false) }
    val last = ui.report?.let { it.hash to ui.reportUrl } ?: ui.pickup?.let { it.hash to ui.pickupUrl }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(32.dp), color = c.raised, modifier = Modifier.padding(32.dp).widthIn(max = 920.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(40.dp)) {
                Text("직원 설정", style = MaterialTheme.typography.headlineMedium, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Text("차량 $vehicleId  ·  데이터 $sourceLabel", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                if (showQr && last != null) {
                    Spacer(Modifier.height(32.dp))
                    QrPanel(last.second, last.first, Modifier.align(Alignment.CenterHorizontally), qrSize = 360.dp)
                } else {
                    if (ui.demo) {
                        StaffSection("시연 조작")
                        Text(
                            if (fakeOnly) "가짜 차량이라 문·창문·라이트·좌석도 아래 버튼으로 바꿉니다."
                            else "문·창문·라이트·좌석·경고등은 Pleos 에뮬레이터의 Virtual Console로 바꿉니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.ink2,
                        )
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChagokButton("대여 중 변화 적용", demo::driveDuringRental, kind = ButtonKind.Secondary)
                            if (fakeOnly) {
                                ChagokButton("조수석 창문", demo::toggleWindow, kind = ButtonKind.Secondary)
                                ChagokButton("미등", demo::toggleTailLamp, kind = ButtonKind.Secondary)
                                ChagokButton("조수석 좌석", demo::toggleSeat, kind = ButtonKind.Secondary)
                            }
                            ChagokButton("처음 상태로", demo::reset, kind = ButtonKind.Secondary)
                        }
                    }
                    if (last != null) {
                        StaffSection("기록")
                        ChagokButton("마지막 QR 다시 보기", { showQr = true }, kind = ButtonKind.Secondary, icon = R.drawable.ic_phone)
                    }
                    if (ui.state == RentalState.IN_RENTAL || ui.state == RentalState.RETURN_CHECK) {
                        StaffSection("무효 처리")
                        Text("무효로 처리하면 이 대여는 리포트를 만들지 않습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("차량 교체", "기록 오류").forEach { reason ->
                                ChagokButton(reason, { onVoid(reason); onDismiss() }, kind = ButtonKind.Danger)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
                ActionRow {
                    if (showQr) ChagokButton("돌아가기", { showQr = false }, kind = ButtonKind.Secondary)
                    ChagokButton("닫기", onDismiss, modifier = Modifier.widthIn(min = 240.dp))
                }
            }
        }
    }
}

@Composable
private fun StaffSection(title: String) {
    val c = ChagokTheme.colors
    Spacer(Modifier.height(36.dp))
    HorizontalDivider(color = c.rule)
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
    Spacer(Modifier.height(8.dp))
}

/** 넓은 창은 왼쪽에 차 그림, 오른쪽에 내용. 좁은 창은 차 그림을 위에 작게 둔다. */
@Composable
private fun WithMap(map: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (LocalWide.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.Center) { map() }
            Box(Modifier.weight(0.6f).fillMaxHeight()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) { map() }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** 주 버튼은 늘 오른쪽 아래에 둔다. */
@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RecordHeading(title: String, meta: String) {
    val c = ChagokTheme.colors
    Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
    Spacer(Modifier.height(12.dp))
    Text(meta, style = MaterialTheme.typography.bodyLarge.tabular, color = c.ink2)
}

@Composable
private fun Note(icon: Int, text: String) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = c.ink2, modifier = Modifier.padding(top = 4.dp).size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    }
}

@Composable
private fun ParkFirst(text: String) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = c.ink2, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowTags(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(SEOUL)
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(SEOUL)

private fun time(at: Instant): String = DAY_TIME.format(at)

/** 같은 날이면 끝 시각에서 날짜를 뺀다. 예: 11.01 12:39 → 12:41 */
private fun span(from: Instant, to: Instant): String {
    val sameDay = from.atZone(SEOUL).toLocalDate() == to.atZone(SEOUL).toLocalDate()
    return "${time(from)} → ${if (sameDay) TIME_ONLY.format(to) else time(to)}"
}
