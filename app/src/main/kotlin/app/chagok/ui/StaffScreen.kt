package app.chagok.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.chagok.PlatformFacts
import app.chagok.R
import app.chagok.ValueSource
import app.chagok.core.rental.Diagnosis
import app.chagok.core.rental.ItemSupport
import app.chagok.core.rental.RentalState
import app.chagok.core.voice.VoiceEngine
import app.chagok.demo.DemoVehicle
import app.chagok.rental.DeclinedNotice
import app.chagok.rental.RentalUi
import app.chagok.rental.StoredSession
import kotlinx.coroutines.delay
import java.time.Instant

/** PIN을 받은 뒤에 할 일 */
private sealed interface StaffAction {
    val title: String
    val detail: String

    data class Void(val reason: String) : StaffAction {
        override val title: String get() = "무효 처리 · $reason"
        override val detail: String get() = "이 대여는 리포트를 만들지 않습니다."
    }

    data object StaffReturn : StaffAction {
        override val title: String get() = "직원 반납 기록"
        override val detail: String get() = "반납 체크 없이 지금 상태로 기록합니다. 리포트에 직원 기록으로 남습니다."
    }

    data object OpenRecords : StaffAction {
        override val title: String get() = "기록 목록 열기"
        override val detail: String get() = "지난 기록과 QR은 직원만 볼 수 있습니다."
    }
}

/**
 * V-07 직원 화면. 위에서 아래로 상태(차량, 데이터 출처, 음성 엔진, 내비, Gleo, 경고등 권한), 시연 조작, SDK 진단, 기록 목록(저장된 기록, 거절 기록, QR 재발급), 위험한 동작 순서다.
 * 기록 목록과 무효, 직원 반납 기록은 4자리 PIN을 받는다. 어떤 고객이든 이 화면을 열 수는 있어도 지난 고객의 기록을 보거나, 기록을 끝내거나 없앨 수는 없다.
 * 차의 값은 개인정보가 아니라 진단은 PIN 없이 보인다. 목록을 연 채 입력 없이 3분이 지나면 화면을 닫고, 화면을 떠나면 다시 잠긴다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StaffPanel(
    ui: RentalUi,
    source: ValueSource,
    voiceEngine: VoiceEngine,
    platform: PlatformFacts,
    demo: DemoVehicle,
    onSimulateArrival: () -> Unit,
    onVoid: (String) -> Unit,
    onStaffReturn: () -> Unit,
    checkPin: (String) -> Boolean,
    pinLockedMillis: () -> Long,
    onUnlockRecords: (String) -> Boolean,
    onOpenRecord: (String) -> Unit,
    onCloseRecord: () -> Unit,
    onReadDiagnosis: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = ChagokTheme.colors
    var pinFor by remember { mutableStateOf<StaffAction?>(null) }
    val opened = ui.opened
    // 어떤 길로 떠나든(닫기, 바깥 누름, 화면 재생성) 다음에 열 때 PIN을 다시 받는다.
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(Unit) { onDispose { leave() } }
    // 이 창의 입력은 앱 화면의 입력 감시에 닿지 않으므로 따로 센다.
    val input = remember { ScreenInput() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (ui.recordsUnlocked) CompositionLocalProvider(LocalScreenInput provides input) { AfterIdle(onDismiss) }
        Surface(shape = RoundedCornerShape(32.dp), color = c.raised, modifier = Modifier.padding(32.dp).widthIn(max = 920.dp).inputGuard(input)) {
            Column(Modifier.padding(vertical = 32.dp)) {
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f, fill = false).fadeBottom(scroll).verticalScroll(scroll).padding(horizontal = 40.dp)) {
                    val action = pinFor
                    when {
                        action != null -> PinPad(
                            action,
                            if (action == StaffAction.OpenRecords) onUnlockRecords else checkPin,
                            pinLockedMillis,
                            onPassed = {
                                pinFor = null
                                when (action) {
                                    is StaffAction.Void -> {
                                        onVoid(action.reason)
                                        onDismiss()
                                    }
                                    StaffAction.StaffReturn -> {
                                        onStaffReturn()
                                        onDismiss()
                                    }
                                    StaffAction.OpenRecords -> Unit
                                }
                            },
                        )
                        opened != null -> {
                            Text("기록 번호 ${opened.recordNo}", style = MaterialTheme.typography.headlineMedium.tabular, color = c.ink)
                            Spacer(Modifier.height(20.dp))
                            QrPanel(opened.url, hash = opened.hash)
                        }
                        else -> {
                            Text("직원 화면", style = MaterialTheme.typography.headlineMedium, color = c.ink)
                            Spacer(Modifier.height(16.dp))
                            StaffStatus(ui, source, voiceEngine, platform)
                            StaffDemo(ui, source, demo, onSimulateArrival, onDismiss)
                            StaffDiagnosis(ui.diagnosis, onReadDiagnosis)
                            if (ui.recordsUnlocked) {
                                StaffRecords(ui.records, ui.declines, onOpenRecord)
                            } else {
                                StaffRecordsLocked { pinFor = StaffAction.OpenRecords }
                            }
                            StaffDanger(ui) { pinFor = it }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                ActionRow(Modifier.padding(horizontal = 40.dp)) {
                    when {
                        pinFor != null -> ChagokButton("돌아가기", { pinFor = null }, kind = ButtonKind.Secondary)
                        opened != null -> ChagokButton("돌아가기", onCloseRecord, kind = ButtonKind.Secondary)
                    }
                    ChagokButton("닫기", onDismiss, kind = if (pinFor == null && opened == null) ButtonKind.Primary else ButtonKind.Secondary, modifier = Modifier.widthIn(min = 240.dp))
                }
            }
        }
    }
}

@Composable
private fun StaffStatus(ui: RentalUi, source: ValueSource, voiceEngine: VoiceEngine, platform: PlatformFacts) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusLine("차량", ui.vehicle)
        if (!ui.vinRead) StatusLine("", "차대번호를 읽지 못해 업체 등록 번호로 기록합니다.")
        StatusLine("데이터 출처", source.label)
        StatusLine("음성 엔진", voiceEngine.label)
        // 내비는 대여가 열려 있는 동안만 연결한다.
        StatusLine("내비", if (ui.naviReady) "연결됨" else if (ui.pickup == null) "대여가 열리면 연결합니다" else "연결 안 됨")
        StatusLine("Gleo 스키마", platform.gleoFunctions?.let { "있음 · 함수 ${it}개" } ?: "없음")
        StatusLine("경고등 권한", if (platform.telltaleGranted) "PRIVATE_CAR_SAFETY 허용" else "PRIVATE_CAR_SAFETY 없음")
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    val c = ChagokTheme.colors
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.width(180.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (label.isEmpty()) c.ink2 else c.ink, modifier = Modifier.weight(1f))
    }
}

/** 실제 차가 아닐 때만 보인다. 실차에는 도착 재현도 시연 조작도 없다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffDemo(ui: RentalUi, source: ValueSource, demo: DemoVehicle, onSimulateArrival: () -> Unit, dismiss: () -> Unit) {
    val c = ChagokTheme.colors
    val canArrive = source != ValueSource.PLEOS && ui.state == RentalState.IN_RENTAL && ui.damaged == null
    if (!canArrive && !ui.demo) return
    val fakeOnly = source == ValueSource.FAKE
    StaffSection("시연 조작")
    if (canArrive) {
        // Virtual Console에는 도착 트리거가 없다.
        Text("에뮬레이터는 차가 움직이지 않아 도착 이벤트가 오지 않습니다. 5초 뒤에 같은 길로 도착을 넘깁니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        Spacer(Modifier.height(12.dp))
        ChagokButton(
            "도착 재현",
            {
                onSimulateArrival()
                dismiss()
            },
            kind = ButtonKind.Secondary,
            icon = R.drawable.ic_car,
        )
    }
    if (ui.demo) {
        if (canArrive) Spacer(Modifier.height(20.dp))
        Text(
            if (fakeOnly) "가짜 차량이라 문·창문·라이트·좌석도 아래 버튼으로 바꿉니다."
            else "문·창문·라이트·좌석·경고등은 Pleos 에뮬레이터의 Virtual Console로 바꿉니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink2,
        )
        Spacer(Modifier.height(12.dp))
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
}

// 진단을 다시 읽는 간격. 이 항목이 화면에 있고 앱이 보일 때만 돈다.
private const val DIAGNOSIS_EVERY_MILLIS = 3_000L

/**
 * 지금 SDK가 무엇을 주는지(V-07). 위 줄의 숫자로 먼저 판단하고, 아래 표는 항목마다 상태와 지금 값, 마지막 콜백 시각을 보여 준다.
 * 실패를 맨 위에 두고, 미지원은 값이 없으니 이름만 한 줄로 모은다. 읽기만 하고 아무것도 저장하지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffDiagnosis(diagnosis: Diagnosis?, onRead: () -> Unit) {
    val c = ChagokTheme.colors
    val read by rememberUpdatedState(onRead)
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                read()
                delay(DIAGNOSIS_EVERY_MILLIS)
            }
        }
    }
    StaffSection("SDK 진단")
    if (diagnosis == null) {
        Text("차를 읽는 중입니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        return
    }
    val failed = diagnosis.count(ItemSupport.FAILED)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Tag("${ItemSupport.SUPPORTED.label} ${diagnosis.count(ItemSupport.SUPPORTED)}", color = c.good, container = c.goodSoft, icon = R.drawable.ic_check_circle)
        Tag("${ItemSupport.UNSUPPORTED.label} ${diagnosis.count(ItemSupport.UNSUPPORTED)}", color = c.ink2, container = c.sunken)
        Tag(
            "${ItemSupport.FAILED.label} $failed",
            color = if (failed > 0) c.bad else c.ink2,
            container = if (failed > 0) c.badSoft else c.sunken,
            icon = if (failed > 0) R.drawable.ic_error else null,
        )
        Text("${clock(diagnosis.readAt)} 읽음", style = MaterialTheme.typography.bodySmall.tabular, color = c.ink2)
    }
    Spacer(Modifier.height(12.dp))
    DiagnosisLine("항목", "상태", "지금 값", "마지막 콜백", header = true)
    (diagnosis.rows.filter { it.support == ItemSupport.FAILED } + diagnosis.rows.filter { it.support == ItemSupport.SUPPORTED }).forEach { row ->
        val failedRow = row.support == ItemSupport.FAILED
        DiagnosisLine(
            row.item.label,
            row.support.label,
            if (failedRow) row.reason.orEmpty() else row.value.orEmpty(),
            row.lastCallback?.let(::clock) ?: "콜백 없음",
            failed = failedRow,
        )
    }
    val unsupported = diagnosis.rows.filter { it.support == ItemSupport.UNSUPPORTED }
    if (unsupported.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "${ItemSupport.UNSUPPORTED.label}: ${unsupported.joinToString("·") { it.item.label }}",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink2,
        )
    }
}

@Composable
private fun DiagnosisLine(item: String, support: String, value: String, callback: String, header: Boolean = false, failed: Boolean = false) {
    val c = ChagokTheme.colors
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall.tabular
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(item, style = style, color = if (header) c.ink2 else c.ink, modifier = Modifier.width(196.dp))
            Text(support, style = style, color = if (failed) c.bad else c.ink2, modifier = Modifier.width(76.dp))
            Text(value, style = style, color = if (header) c.ink2 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(callback, style = style, color = c.ink2, maxLines = 1, modifier = Modifier.width(132.dp))
        }
        HorizontalDivider(color = c.rule)
    }
}

/** 잠긴 기록 목록. 무엇을 보려면 무엇이 필요한지만 적는다. 몇 건인지도 보이지 않는다. */
@Composable
private fun StaffRecordsLocked(onOpen: () -> Unit) {
    val c = ChagokTheme.colors
    StaffSection("기록 목록")
    Text("지난 기록과 QR은 다음 고객이 볼 수 없게 잠겨 있습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    Spacer(Modifier.height(12.dp))
    ChagokButton("기록 목록 열기", onOpen, kind = ButtonKind.Secondary, icon = R.drawable.ic_lock)
}

private const val SHOWN_RECORDS = 8

/** 기록 목록. 저장된 기록과 거절 기록을 시간순으로 함께 보여 주고, 기록마다 QR을 다시 낼 수 있다. */
@Composable
private fun StaffRecords(records: List<StoredSession>, declines: List<DeclinedNotice>, onOpenRecord: (String) -> Unit) {
    val c = ChagokTheme.colors
    StaffSection("기록 목록")
    if (records.isEmpty() && declines.isEmpty()) {
        Text("저장된 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        return
    }
    val lines: List<Pair<Instant, @Composable () -> Unit>> =
        records.map { record -> (record.pickupAt ?: Instant.MIN) to @Composable { RecordLine(record, onOpenRecord) } } +
            declines.map { decline -> decline.at to @Composable { DeclineLine(decline) } }
    val shown = lines.sortedByDescending { it.first }
    shown.take(SHOWN_RECORDS).forEach { (_, line) -> line() }
    if (shown.size > SHOWN_RECORDS) {
        Spacer(Modifier.height(8.dp))
        Text("외 ${shown.size - SHOWN_RECORDS}건", style = MaterialTheme.typography.bodySmall.tabular, color = c.ink2)
    }
}

@Composable
private fun RecordLine(record: StoredSession, onOpenRecord: (String) -> Unit) {
    val c = ChagokTheme.colors
    val readable = record.status != StoredSession.Status.DAMAGED
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(record.recordNo ?: "번호 없음", style = MaterialTheme.typography.titleMedium.tabular, color = if (readable) c.ink else c.ink2)
            Text(
                listOfNotNull(record.status.label, record.pickupAt?.let(::time)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall.tabular,
                color = c.ink2,
            )
        }
        Spacer(Modifier.width(16.dp))
        // 무효로 끝난 대여도 인수 기록의 QR은 다시 낼 수 있다. 내용이 맞지 않는 기록은 내지 않는다.
        if (readable) ChagokButton("QR", { onOpenRecord(record.sessionId) }, kind = ButtonKind.Secondary, icon = R.drawable.ic_qr)
    }
    HorizontalDivider(color = c.rule)
}

@Composable
private fun DeclineLine(decline: DeclinedNotice) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 6.dp)) {
        Icon(painterResource(R.drawable.ic_block), contentDescription = null, tint = c.ink2, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("기록 거절", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text("${time(decline.at)}  ·  차량 ${decline.vehicle}", style = MaterialTheme.typography.bodySmall.tabular, color = c.ink2)
        }
    }
    HorizontalDivider(color = c.rule)
}

private val StoredSession.Status.label: String
    get() = when (this) {
        StoredSession.Status.PICKUP -> "인수 기록"
        StoredSession.Status.RETURNED -> "반납 리포트"
        StoredSession.Status.VOIDED -> "무효"
        StoredSession.Status.DAMAGED -> "읽지 못함"
    }

/** 위험한 동작. 대여가 열려 있을 때만 보이고 맨 아래에 둔다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffDanger(ui: RentalUi, onAction: (StaffAction) -> Unit) {
    val c = ChagokTheme.colors
    if (ui.state != RentalState.IN_RENTAL && ui.state != RentalState.RETURN_CHECK) return
    if (ui.damaged == null && ui.pickup != null) {
        StaffSection("직원 반납 기록")
        Text("고객이 차를 두고 떠났을 때 반납 체크 없이 기록합니다. 리포트에 직원 기록으로 남습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        Spacer(Modifier.height(12.dp))
        ChagokButton("직원 반납 기록 남기기", { onAction(StaffAction.StaffReturn) }, kind = ButtonKind.Secondary, enabled = ui.parked, icon = R.drawable.ic_lock)
    }
    StaffSection("무효 처리")
    Text("무효로 처리하면 이 대여는 리포트를 만들지 않습니다. 되돌릴 수 없습니다.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("차량 교체", "기록 오류").forEach { reason ->
            ChagokButton(reason, { onAction(StaffAction.Void(reason)) }, kind = ButtonKind.Danger, icon = R.drawable.ic_lock)
        }
    }
}

@Composable
private fun StaffSection(title: String) {
    val c = ChagokTheme.colors
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = c.rule)
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
    Spacer(Modifier.height(8.dp))
}

private const val PIN_LENGTH = 4

/** 직원 PIN 숫자 패드. 한 손으로 누를 수 있게 칸을 크게 만든다. 네 자리를 누르면 바로 확인한다. 틀린 횟수와 잠금은 컨트롤러가 센다. '돌아가기'로 나갔다 들어와도 풀리지 않는다. */
@Composable
private fun PinPad(action: StaffAction, checkPin: (String) -> Boolean, lockedMillis: () -> Long, onPassed: () -> Unit) {
    val c = ChagokTheme.colors
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(lockedMillis() > 0) }
    LaunchedEffect(locked) {
        if (locked) {
            delay(lockedMillis())
            locked = false
            wrong = 0
        }
    }
    val press = { digit: Char ->
        if (pin.length < PIN_LENGTH) {
            pin += digit
            if (pin.length == PIN_LENGTH) {
                if (checkPin(pin)) {
                    onPassed()
                } else {
                    pin = ""
                    wrong++
                    locked = lockedMillis() > 0
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(action.title, style = MaterialTheme.typography.headlineMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Text(action.detail, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(PIN_LENGTH) { i ->
                Box(
                    Modifier
                        .size(28.dp)
                        .then(if (i < pin.length) Modifier.background(c.ink, CircleShape) else Modifier.border(2.dp, c.ink2, CircleShape)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                locked -> "잠시 뒤에 다시 입력해 주세요"
                wrong > 0 -> "PIN이 맞지 않습니다"
                else -> "직원 PIN 4자리를 입력해 주세요"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (locked || wrong > 0) c.bad else c.ink2,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { digit -> PinKey(enabled = !locked, onClick = { press(digit) }) { PinDigit(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PinKey(enabled = !locked && pin.isNotEmpty(), onClick = { pin = pin.dropLast(1) }, description = "지우기") {
                    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = c.ink, modifier = Modifier.size(44.dp))
                }
                PinKey(enabled = !locked, onClick = { press('0') }) { PinDigit('0') }
                Spacer(Modifier.size(width = 152.dp, height = 104.dp))
            }
        }
    }
}

@Composable
private fun PinDigit(digit: Char) {
    Text("$digit", style = MaterialTheme.typography.headlineMedium.tabular, color = ChagokTheme.colors.ink)
}

@Composable
private fun PinKey(enabled: Boolean, onClick: () -> Unit, description: String? = null, content: @Composable () -> Unit) {
    val c = ChagokTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(if (pressed) 90 else 180), label = "pinPress")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = c.sunken,
        interactionSource = source,
        modifier = Modifier
            .size(width = 152.dp, height = 104.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .semantics { if (description != null) contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
