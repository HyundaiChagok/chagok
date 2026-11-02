package app.chagok.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chagok.PlatformFacts
import app.chagok.R
import app.chagok.ValueSource
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalState
import app.chagok.core.voice.VoiceEngine
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
 * 앱 창은 화면 오른쪽에 뜨고 운전자는 왼쪽에 앉는다. 그래서 내용과 버튼, QR은 창의 왼쪽에, 차 그림은 오른쪽에 둔다.
 * 넓은 창(가로 1200dp 이상)은 둘을 나란히 두고, 좁은 창(앱 두 개를 띄운 세로 창)은 위아래로 쌓는다.
 */
@Composable
fun ChagokScreen(
    controller: RentalController,
    demo: DemoVehicle,
    source: ValueSource,
    speaking: StateFlow<Boolean>,
    voiceEngine: StateFlow<VoiceEngine>,
    platform: PlatformFacts,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val talking by speaking.collectAsStateWithLifecycle()
    val engine by voiceEngine.collectAsStateWithLifecycle()
    var staffOpen by remember { mutableStateOf(false) }
    // 복구한 대여는 고객이 이미 QR을 받았으니 인수 기록 화면을 다시 띄우지 않는다.
    var pickupSeen by rememberSaveable(ui.pickup?.hash) { mutableStateOf(ui.restored) }
    // Gleo가 인수 기록을 보여 달라고 했다.
    LaunchedEffect(ui.recordRequests) { if (ui.recordRequests > 0) pickupSeen = false }
    val c = ChagokTheme.colors
    val current = Shown(ui, pickupSeen)
    val input = remember { ScreenInput() }
    // 앞 화면의 버튼을 두 번 눌러 다음 화면의 같은 자리 버튼까지 눌리지 않게 한다.
    LaunchedEffect(current.screen) { input.holdAfterChange() }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.paper).inputGuard(input)) {
        val wide = maxWidth >= 1200.dp
        CompositionLocalProvider(LocalWide provides wide, LocalScreenInput provides input) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 32.dp, vertical = 20.dp)) {
                AppHeader(ui.state, source, onStaff = { staffOpen = true })
                // 자막은 그 말을 한 화면에서만 보인다. 인수 때의 'QR을 받으세요'가 대여 중 화면에 남지 않게 한다.
                val captionScreen = remember(ui.caption) { current.screen }
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.screen },
                    transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                    label = "screen",
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = if (wide) 24.dp else 12.dp),
                ) { shown ->
                    val s = shown.ui
                    when (shown.screen) {
                        Screen.RESTORING -> Reading("저장된 기록을 확인하는 중입니다", "기록 번호와 무결성 값을 맞춰 봅니다")
                        Screen.IDLE -> Idle(s.vehicle, s.parked, controller::startPickup)
                        Screen.NOTICE -> Notice(controller::acceptNotice, controller::declineNotice)
                        Screen.READING_PICKUP -> Reading("차량 상태를 읽는 중입니다", "잔량·경고등·문·창문·라이트 등 이 차가 알려 주는 항목")
                        Screen.PICKUP -> s.pickup?.let { PickupDone(it, s.pickupUrl, onDone = { pickupSeen = true }) }
                        Screen.RENTAL -> InRental(
                            ui = s,
                            onReturnCheck = controller::startReturnCheck,
                            onShowPickup = { pickupSeen = false },
                            onGoToReturn = controller::planReturnTrip,
                            onStartRoute = controller::startReturnRoute,
                            onChargers = controller::showNearbyChargers,
                            onClosePlan = controller::closeReturnPlan,
                        )
                        Screen.DAMAGED -> Damaged(s.damaged.orEmpty(), onStaff = { staffOpen = true })
                        Screen.CHECK -> ReturnCheckView(s, controller::cancelReturnCheck, controller::confirmReturn)
                        Screen.READING_RETURN -> Reading("반납 기록을 남기는 중입니다", "인수 때와 지금을 항목별로 비교합니다")
                        Screen.REPORT -> s.report?.let { ReportView(it, s.reportUrl, controller::close) } ?: ReportUnreadable(controller::close)
                        Screen.VOIDED -> Voided(controller::close)
                    }
                }
                CaptionBar(
                    caption = ui.caption.takeIf { captionScreen == current.screen },
                    error = ui.error ?: ui.damaged.takeIf { current.screen != Screen.DAMAGED },
                    speaking = talking,
                    listening = ui.listening,
                )
            }
        }
    }

    if (staffOpen) {
        StaffPanel(
            ui, source, engine, platform, demo,
            onSimulateArrival = controller::simulateArrival,
            onVoid = controller::void,
            onStaffReturn = controller::recordStaffReturn,
            checkPin = controller::checkStaffPin,
            pinLockedMillis = controller::staffPinLockedMillis,
            onUnlockRecords = controller::unlockRecords,
            onOpenRecord = controller::openRecord,
            onCloseRecord = controller::closeRecord,
            onReadDiagnosis = controller::readDiagnosis,
            onLeave = controller::leaveStaffPanel,
            onDismiss = { staffOpen = false },
        )
    }
}

private enum class Screen { RESTORING, IDLE, NOTICE, READING_PICKUP, PICKUP, RENTAL, DAMAGED, CHECK, READING_RETURN, REPORT, VOIDED }

/** 화면 전환은 [screen]이 바뀔 때만 일어난다. 같은 화면 안의 값 변화(반납 체크 목록 등)는 제자리에서 바뀐다. */
private data class Shown(val ui: RentalUi, val pickupSeen: Boolean) {
    val screen: Screen
        get() = if (ui.restoring) Screen.RESTORING else when (ui.state) {
            RentalState.IDLE -> Screen.IDLE
            RentalState.NOTICE -> Screen.NOTICE
            RentalState.PICKUP -> Screen.READING_PICKUP
            RentalState.IN_RENTAL -> when {
                ui.damaged != null -> Screen.DAMAGED
                ui.pickup != null && !pickupSeen -> Screen.PICKUP
                else -> Screen.RENTAL
            }
            RentalState.RETURN_CHECK -> Screen.CHECK
            RentalState.RETURN -> Screen.READING_RETURN
            RentalState.RETURNED -> Screen.REPORT
            RentalState.VOIDED -> Screen.VOIDED
        }
}

// 화면이 바뀐 뒤 입력을 막는 시간. 연타의 두 번째 누름을 막을 만큼만 잡는다.
private const val HOLD_AFTER_CHANGE_MILLIS = 600L

// QR을 띄워 두는 시간. 입력이 없으면 내려서 다음 사람에게 앞 기록이 보이지 않게 한다.
private const val QR_IDLE_MILLIS = 180_000L

/** 화면의 입력 상태. 화면이 바뀐 직후의 입력을 막고, 마지막 입력 뒤로 얼마나 지났는지 재는 데 쓴다. */
@Stable
internal class ScreenInput {
    /** 입력이 올 때마다 늘어난다. */
    var count by mutableIntStateOf(0)
        private set

    var held by mutableStateOf(false)
        private set

    fun touched() {
        count++
    }

    suspend fun holdAfterChange() {
        held = true
        delay(HOLD_AFTER_CHANGE_MILLIS)
        held = false
    }
}

internal val LocalScreenInput = staticCompositionLocalOf { ScreenInput() }

// 창 전체의 입력을 자식보다 먼저 본다. 막는 동안에는 소비해 버튼까지 가지 않게 한다.
// 실제로 누른 것만 입력으로 센다. 시스템이 재시작하며 보내는 취소나 호버 이벤트로 QR 시간이 늘어나면 안 된다.
internal fun Modifier.inputGuard(input: ScreenInput): Modifier = pointerInput(input) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed }) input.touched()
            if (input.held) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * 입력 없이 3분이 지나면 [onIdle]을 부른다. QR을 내릴 때 쓴다.
 * 마지막 입력 시각을 저장해 두고 그 시각부터 잰다. 화면이 다시 만들어져도(차량 HAL이 죽으면 시스템이 앱 화면을 다시 만들 수 있다) 3분을 처음부터 세지 않는다.
 */
@Composable
internal fun AfterIdle(onIdle: () -> Unit) {
    val input = LocalScreenInput.current
    val latest by rememberUpdatedState(onIdle)
    var lastInput by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var seenCount by remember { mutableIntStateOf(input.count) }
    LaunchedEffect(input.count) {
        val now = SystemClock.elapsedRealtime()
        if (input.count != seenCount) {
            seenCount = input.count
            lastInput = now
        }
        // 재부팅하면 저장한 시각이 지금보다 뒤일 수 있다. 그러면 지금부터 잰다.
        delay(idleLeftMillis(lastInput, now))
        latest()
    }
}

/** 마지막 입력 뒤로 3분이 차기까지 남은 시간. 마지막 입력이 지금보다 뒤면(재부팅) 3분을 다 준다. */
internal fun idleLeftMillis(lastInput: Long, now: Long): Long =
    if (lastInput > now) QR_IDLE_MILLIS else (lastInput + QR_IDLE_MILLIS - now).coerceAtLeast(0)

/**
 * 차 그림이 있는 화면의 틀. 넓은 창은 내용과 버튼을 왼쪽(운전석 쪽)에, 차 그림을 오른쪽에 둔다.
 * 좁은 창은 위 띠의 왼쪽에 [lead], 오른쪽에 작은 차 그림을 두고, 그 아래에 [body]와 버튼을 쌓는다. 버튼은 어느 창이든 왼쪽 아래다.
 * [centered]면 넓은 창에서 내용을 세로 가운데에 둔다(내용이 적은 화면).
 */
@Composable
internal fun WithMap(
    map: @Composable () -> Unit,
    lead: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
    centered: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    if (LocalWide.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.weight(0.6f).fillMaxHeight()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top) {
                    lead()
                    body()
                }
                Spacer(Modifier.height(24.dp))
                actions()
            }
            Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.Center) { map() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { lead() }
                Spacer(Modifier.width(24.dp))
                Box(Modifier.size(width = 168.dp, height = 240.dp), contentAlignment = Alignment.Center) { map() }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f).fillMaxWidth()) { body() }
            Spacer(Modifier.height(20.dp))
            actions()
        }
    }
}

/**
 * QR이 있는 화면의 틀(V-03, V-06). 넓은 창은 QR과 버튼을 왼쪽(운전석 쪽)에, 내용을 오른쪽에 둔다.
 * 좁은 창은 [head]와 QR이 스크롤 없이 함께 보이도록 쌓고, 나머지([rest])는 그 아래로 스크롤한다. QR을 내리면([qrShown]이 false) 내용이 창을 채운다.
 */
@Composable
internal fun WithQr(
    url: String?,
    qrShown: Boolean,
    actions: @Composable () -> Unit,
    head: @Composable ColumnScope.() -> Unit,
    rest: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    if (LocalWide.current && qrShown) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(QR_BOARD).fillMaxHeight()) {
                QrPanel(url)
                Spacer(Modifier.weight(1f))
                actions()
            }
            Column(Modifier.weight(1f).fillMaxHeight().fadeBottom(scroll).verticalScroll(scroll)) {
                head()
                rest()
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().fadeBottom(scroll).verticalScroll(scroll)) {
                head()
                if (qrShown) {
                    Spacer(Modifier.height(16.dp))
                    QrPanel(url)
                }
                rest()
            }
            Spacer(Modifier.height(20.dp))
            actions()
        }
    }
}

@Composable
internal fun RecordHeading(title: String, meta: String) {
    val c = ChagokTheme.colors
    Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
    Spacer(Modifier.height(12.dp))
    Text(meta, style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
}

@Composable
internal fun Note(icon: Int, text: String) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = c.ink2, modifier = Modifier.padding(top = 4.dp).size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    }
}

@Composable
internal fun ParkFirst(text: String) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = c.ink2, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    }
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(SEOUL)
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(SEOUL)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SEOUL)

internal fun time(at: Instant): String = DAY_TIME.format(at)

/** 초까지 적은 시각. 직원 화면의 진단에 쓴다. */
internal fun clock(at: Instant): String = CLOCK.format(at)

/** 같은 날이면 끝 시각에서 날짜를 뺀다. 예: 11.01 12:39 → 12:41. 1분이 안 되면 한 시각만 쓴다. 예: 11.01 12:40 (1분 미만) */
internal fun span(from: Instant, to: Instant): String {
    if (Rental.underAMinute(from, to)) return "${time(from)} (${Rental.UNDER_A_MINUTE})"
    val sameDay = from.atZone(SEOUL).toLocalDate() == to.atZone(SEOUL).toLocalDate()
    return "${time(from)} → ${if (sameDay) TIME_ONLY.format(to) else time(to)}"
}
