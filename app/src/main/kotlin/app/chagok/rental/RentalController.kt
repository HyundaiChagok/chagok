package app.chagok.rental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chagok.AppSettings
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.RecordNumber
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalEvent
import app.chagok.core.rental.RentalRecorder
import app.chagok.core.rental.RentalReport
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ReturnCheck
import app.chagok.core.rental.ShareCode
import app.chagok.core.rental.Voice
import app.chagok.core.rental.next
import app.chagok.core.session.ActiveSession
import app.chagok.core.session.SessionRecorder
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * 화면이 그리는 상태. caption은 마지막 음성 안내이고, TTS가 없어도 같은 문구를 자막으로 보여 준다.
 * checkReady는 반납 체크의 첫 판정이 나왔는지다. 그 전의 빈 목록을 "확인할 것 없음"으로 보이지 않게 한다.
 */
data class RentalUi(
    val state: RentalState = RentalState.IDLE,
    val demo: Boolean,
    val parked: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val pickup: SnapshotRecord? = null,
    val pickupUrl: String? = null,
    val check: List<CheckItem> = emptyList(),
    val checkReady: Boolean = false,
    val report: RentalReport? = null,
    val reportUrl: String? = null,
    val caption: String? = null,
)

/**
 * 한 번의 대여를 끌고 간다(기능 정의서 03절 상태 전이, F-SES·F-SNP·F-RET·F-SHR).
 * 상태 이동은 :core의 전이표([next])만 따르고, 전이표에 없는 버튼 입력은 무시한다.
 */
class RentalController(
    private val vehicle: VehicleSource,
    private val store: RecordStore,
    private val speaker: Speaker,
    private val settings: AppSettings,
    parked: Flow<Boolean>,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val recorder = RentalRecorder(vehicle, settings.vehicleId, settings.appVersion, settings.demo, now)
    private val _ui = MutableStateFlow(RentalUi(demo = settings.demo))
    val ui: StateFlow<RentalUi> = _ui.asStateFlow()

    @Volatile
    private var timeline: ActiveSession? = null
    private var checkJob: Job? = null

    init {
        viewModelScope.launch { parked.collect { p -> _ui.update { it.copy(parked = p) } } }
    }

    fun startPickup() {
        if (move(RentalEvent.StartPickup)) say(Voice.notice())
    }

    fun declineNotice() {
        if (move(RentalEvent.DeclineNotice)) _ui.update { RentalUi(demo = it.demo, parked = it.parked, caption = "업체 안내에 따라 점검해 주세요.") }
    }

    fun acceptNotice() {
        // 기록 저장·압축·해시 계산은 화면을 막지 않게 메인 스레드 밖에서 한다.
        if (move(RentalEvent.AcceptNotice)) viewModelScope.launch(Dispatchers.Default) { capturePickup() }
    }

    fun startReturnCheck() {
        if (move(RentalEvent.StartReturnCheck)) watchReturnCheck()
    }

    fun cancelReturnCheck() {
        if (move(RentalEvent.CancelReturnCheck)) stopReturnCheck()
    }

    fun confirmReturn() {
        if (!move(RentalEvent.ConfirmReturn)) return
        stopReturnCheck()
        viewModelScope.launch(Dispatchers.Default) { captureReturn() }
    }

    fun close() {
        if (move(RentalEvent.Close)) _ui.update { RentalUi(demo = it.demo, parked = it.parked) }
    }

    /** 직원만 쓴다(기능 정의서 V-07). 무효 세션에는 리포트를 만들지 않는다. */
    fun void(reason: String) {
        val sessionId = _ui.value.pickup?.sessionId
        if (!move(RentalEvent.Void(reason))) return
        stopReturnCheck()
        viewModelScope.launch(Dispatchers.IO) {
            timeline?.finish()
            timeline = null
            sessionId?.let { store.markVoid(it, reason, now()) }
        }
    }

    private suspend fun capturePickup() {
        _ui.update { it.copy(busy = true) }
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val recordNo = RecordNumber.of(settings.vehicleCode, today, store.nextSequence(today))
        val record = captureWithRetry(UUID.randomUUID().toString(), recordNo, SnapshotKind.PICKUP)
        if (record == null) {
            move(RentalEvent.PickupFailed) { copy(busy = false, error = "차량 정보를 읽지 못했습니다. 직원에게 알려 주세요.") }
            return
        }
        store.save(record)
        // 방금 읽은 인수 기록을 시작 상태로 넘겨 차를 한 번 더 읽지 않는다.
        timeline = SessionRecorder(vehicle, now = now).start(viewModelScope, record.snapshot()) { Rental.recordsDuringRental(it) }
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, record) }
        move(RentalEvent.PickupSaved) { copy(busy = false, pickup = record, pickupUrl = url) }
        say(Voice.pickupDone(record.state))
    }

    private suspend fun captureReturn() {
        val pickup = _ui.value.pickup ?: return
        _ui.update { it.copy(busy = true) }
        val record = captureWithRetry(pickup.sessionId, pickup.recordNo, SnapshotKind.RETURN)
        if (record == null) {
            move(RentalEvent.ReturnFailed) { copy(busy = false, error = "차량 정보를 읽지 못했습니다. 다시 시도해 주세요.") }
            watchReturnCheck()
            return
        }
        val events = timeline?.finish(record.snapshot())?.events
        timeline = null
        val report = RentalReport.build(pickup, record, settings.policy, events)
        store.save(record)
        store.save(report)
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, report) }
        move(RentalEvent.ReturnSaved) { copy(busy = false, check = emptyList(), report = report, reportUrl = url) }
        say(Voice.returnDone())
    }

    // 처음 한 번은 전체를 요약해 말하고, 그 뒤로는 고객이 고친 항목만 짧게 알린다(F-RET-02, F-RET-04).
    private fun watchReturnCheck() {
        val pickup = _ui.value.pickup ?: return
        checkJob?.cancel()
        _ui.update { it.copy(check = emptyList(), checkReady = false) }
        checkJob = viewModelScope.launch {
            var previous: List<CheckItem>? = null
            ReturnCheck.watch(vehicle, pickup.state, settings.policy).collect { items ->
                val before = previous
                if (before == null) {
                    say(Voice.returnCheck(items))
                } else {
                    (before - items.toSet()).forEach { done -> Voice.resolved(done, items)?.let(::say) }
                    (items - before.toSet()).forEach { new -> Voice.appeared(new, items)?.let(::say) }
                }
                previous = items
                _ui.update { it.copy(check = items, checkReady = true) }
            }
        }
    }

    private fun stopReturnCheck() {
        checkJob?.cancel()
        checkJob = null
    }

    // 하나도 읽지 못하면 SDK에 아직 연결되지 않은 것으로 보고 1초 간격으로 세 번 다시 읽는다(F-SNP-01).
    private suspend fun captureWithRetry(sessionId: String, recordNo: String, kind: SnapshotKind): SnapshotRecord? {
        repeat(3) { attempt ->
            val record = recorder.capture(sessionId, recordNo, kind)
            if (Rental.RECORDED.any(record.state::has)) return record
            if (attempt < 2) delay(1.seconds)
        }
        return null
    }

    // 상태와 그 화면이 보여 줄 값을 한 번에 바꾼다. 나눠서 바꾸면 그 사이 프레임에 빈 화면이 비친다.
    private fun move(event: RentalEvent, change: RentalUi.() -> RentalUi = { this }): Boolean {
        val next = _ui.value.state.next(event) ?: return false
        _ui.update { it.copy(state = next, error = null).change() }
        return true
    }

    private fun say(text: String) {
        _ui.update { it.copy(caption = text) }
        speaker.say(text)
    }

    // 요약본으로도 QR 한도(1,000B)를 넘으면 링크를 만들지 않는다. 화면은 QR 자리에 직원 안내를 보여 준다.
    private fun shareUrl(make: () -> String): String? = runCatching(make).getOrNull()

    private fun SnapshotRecord.snapshot() = VehicleSnapshot(capturedAt, null, state)

    override fun onCleared() {
        speaker.stop()
    }
}
