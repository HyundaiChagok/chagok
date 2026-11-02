package app.chagok.rental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chagok.AppSettings
import app.chagok.core.gleo.GleoCommands
import app.chagok.core.gleo.GleoFunction
import app.chagok.core.gleo.GleoReply
import app.chagok.core.model.Unavailable
import app.chagok.core.model.Vin
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.Diagnosis
import app.chagok.core.rental.Diagnostics
import app.chagok.core.rental.EnergyGauge
import app.chagok.core.rental.ItemSupport
import app.chagok.core.rental.RecordNumber
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalEvent
import app.chagok.core.rental.RentalRecorder
import app.chagok.core.rental.RentalReport
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ReturnCheck
import app.chagok.core.rental.ReturnPlan
import app.chagok.core.rental.ReturnTrip
import app.chagok.core.rental.ShareCode
import app.chagok.core.rental.Voice
import app.chagok.core.rental.next
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.ReplyListener
import app.chagok.core.voice.SpokenReplies
import app.chagok.core.voice.SpokenReply
import app.chagok.core.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * 화면이 그리는 상태. caption은 마지막 음성 안내이고, TTS가 없어도 같은 문구를 자막으로 보여 준다.
 * checkReady는 반납 체크의 첫 판정이 나왔는지다. 그 전의 빈 목록을 "확인할 것 없음"으로 보이지 않게 한다.
 *
 * restoring은 앱을 켠 뒤 저장된 대여를 읽는 중인지다. 끝나기 전에는 버튼을 그리지 않고, 컨트롤러도 입력을 받지 않는다.
 * restored는 앱을 다시 켜서 이어 가는 대여인지다. 인수 QR 화면을 다시 띄우지 않는다.
 * damaged는 저장된 기록이 무결성 값과 맞지 않아 이어 갈 수 없을 때의 안내다. 반납 체크와 새 인수를 막고 직원 무효 처리로만 푼다.
 * records와 opened는 직원 화면의 저장 기록 목록과, 거기서 연 기록의 QR이다. recordsUnlocked는 직원 PIN으로 목록을 열었는지이고, 직원 화면을 떠나면 다시 잠긴다. 잠겨 있는 동안 둘은 비어 있다.
 * diagnosis는 직원 화면의 SDK 진단이다(V-07). 읽기만 하고 저장하지 않는다.
 * vehicle은 화면에 보일 차량 표시이고(차대번호면 끝 6자리), vinRead는 차대번호를 읽었는지다. 못 읽었으면 업체 등록 번호로 기록한다.
 * listening은 '반납 기록을 남길까요?'의 답을 듣는 중인지다. 화면이 듣는 중임을 보여 준다.
 * recordRequests는 Gleo가 인수 기록을 보여 달라고 한 횟수다. 늘어나면 화면이 인수 기록과 QR을 다시 연다.
 * naviReady는 내비에 연결됐는지다. false면 '반납하러 가기'를 숨긴다. returnTrip은 그 버튼을 누른 뒤 어디까지 왔는지이고, arrived는 반납 지점 도착 이벤트를 받았는지다. 둘 다 저장하지 않는다.
 * energy는 반납 체크의 잔량 게이지다. 차를 읽을 때마다 바뀐다. declines는 직원 화면에 보일 기록 거절 목록이다.
 * log는 지금까지의 대여 중 기록 건수와 공백 수다(V-04).
 */
data class RentalUi(
    val state: RentalState = RentalState.IDLE,
    val demo: Boolean,
    val vehicle: String = "",
    val vinRead: Boolean = false,
    val restoring: Boolean = false,
    val restored: Boolean = false,
    val damaged: String? = null,
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
    val records: List<StoredSession> = emptyList(),
    val opened: SharedRecord? = null,
    val recordsUnlocked: Boolean = false,
    val diagnosis: Diagnosis? = null,
    val naviReady: Boolean = false,
    val returnTrip: ReturnTripStep? = null,
    val arrived: Boolean = false,
    val recordRequests: Int = 0,
    val listening: Boolean = false,
    val energy: EnergyGauge? = null,
    val declines: List<DeclinedNotice> = emptyList(),
    val log: RentalLog = RentalLog(),
)

/** 지금까지의 대여 중 기록. events는 리포트의 '대여 중 기록' 줄 수이고, gaps는 기록하지 못한 구간 수다. */
data class RentalLog(val events: Int = 0, val gaps: Int = 0)

/** '반납하러 가기'를 누른 뒤의 단계 */
sealed interface ReturnTripStep {
    /** 내비에 도착 예상 배터리를 묻는 중 */
    data object Estimating : ReturnTripStep

    /** 비교를 보여 주고 고객의 결정을 기다린다. */
    data class Planned(val plan: ReturnPlan) : ReturnTripStep

    /** 길안내를 요청했다. 값을 받지 못했으면 [plan]이 null이다. */
    data class Guiding(val plan: ReturnPlan?, val pointName: String) : ReturnTripStep
}

/** 저장된 기록으로 다시 만든 QR. url이 null이면 QR 한도를 넘어 만들지 못한 것이다. */
data class SharedRecord(val recordNo: String, val hash: String, val url: String?)

/**
 * 한 번의 대여를 끌고 간다(기능 정의서 03절 상태 전이, F-SES·F-SNP·F-RET·F-SHR).
 * 상태 이동은 :core의 전이표([next])만 따르고, 전이표에 없는 버튼 입력은 무시한다.
 *
 * 상태가 바뀔 때마다 session.json에 저장하고, 앱을 켜면 그 파일로 대여를 이어 간다(F-SES-03). 버튼 입력과 전이, 저장은 모두
 * [line]에서 한 줄로 처리한다. 메인 스레드에서 fsync하면 차가 바쁠 때 ANR이 나고, 저장 순서가 뒤바뀌면 복구가 틀어진다.
 *
 * 대여 중 기록([timeline])은 프로세스에 하나 있고 이 화면이 사라져도 돈다. [onRecording]은 대여가 열리거나 끝날 때 불려 포그라운드 서비스를 켜고 끈다.
 */
class RentalController(
    private val vehicle: VehicleSource,
    private val store: RecordStore,
    private val voice: VoiceOutput,
    private val settings: AppSettings,
    private val parked: Flow<Boolean>,
    private val timeline: TimelineKeeper,
    private val now: () -> Instant = Instant::now,
    private val onClosed: () -> Unit = {},
    private val onRecording: (Boolean) -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
    private val trip: ReturnTripKeeper? = null,
    spoken: Flow<String> = emptyFlow(),
    private val onGleoReply: (GleoReply) -> Unit = {},
    private val replies: ReplyListener? = null,
) : ViewModel() {
    private val recorder = RentalRecorder(vehicle, settings.vehicleId, settings.appVersion, settings.demo, settings.recordSource, now)
    private val _ui = MutableStateFlow(RentalUi(demo = settings.demo, vehicle = settings.vehicleId, restoring = true))
    val ui: StateFlow<RentalUi> = _ui.asStateFlow()

    private val line = io.limitedParallelism(1)

    // 지금 대여. 인수 기록이 손상돼도 무효 처리할 수 있게 인수 기록과 따로 갖는다.
    private var session: OpenSession? = null
    private var checkJob: Job? = null
    private var replyJob: Job? = null

    // 반납 체크에서 마지막으로 판정한 목록. 화면이 뒤로 갔다 돌아와도 이어서 달라진 것만 말하려고 남겨 둔다.
    private var previousCheck: List<CheckItem>? = null

    @Volatile
    private var visible = true

    private var pinWrong = 0
    private var pinLockedUntil: Instant? = null

    @Volatile
    private var frontUntil: Instant? = null

    // 기어 구독. 화면이 보일 때만 듣는다. 다시 보이면 지금 기어가 바로 온다.
    private var parkedJob: Job? = null

    init {
        viewModelScope.launch(line) { restore() }
        // 차대번호는 화면 표시용이다. 읽는 데 오래 걸려도 복구와 버튼을 막지 않게 따로 읽는다.
        viewModelScope.launch(io) {
            val vin = attempt { vehicle.vin() }
            if (vin != null) _ui.update { it.copy(vehicle = Vin.label(vin), vinRead = true) }
        }
        viewModelScope.launch { timeline.saveFailed.collect { showError(SAVE_FAILED) } }
        viewModelScope.launch { timeline.updates.collect { refreshLog() } }
        watchParked()
        trip?.let(::watchReturnTrip)
        viewModelScope.launch { spoken.collect { text -> _ui.update { it.copy(caption = text) } } }
    }

    private fun watchParked() {
        parkedJob = viewModelScope.launch {
            parked.catch { }.collect { p ->
                _ui.update { it.copy(parked = p) }
                if (!p) viewModelScope.launch(line) { leftPark() }
            }
        }
    }

    fun startPickup() = act {
        if (_ui.value.parked && move(RentalEvent.StartPickup)) say(Voice.notice())
    }

    /** 거절도 기록으로 남긴다(시각과 차량만). 저장하지 못해도 고객은 거절한 것이므로 대기로 돌아가고 오류만 보여 준다. */
    fun declineNotice() = act {
        if (move(RentalEvent.DeclineNotice)) {
            val at = now().truncatedTo(ChronoUnit.SECONDS)
            val kept = saved { store.saveDecline(DeclinedNotice(at, _ui.value.vehicle)) }
            _ui.update { it.fresh().copy(caption = DECLINED_CAPTION) }
            if (!kept) showError(SAVE_FAILED)
        }
    }

    /**
     * '출발 전 상태를 남겨 둘까요?' 알림을 눌렀을 때. 앱을 막 켰으면 복원을 기다린다(버튼과 달리 버리면 고객이 고지 화면을 보지 못한다).
     * 닫지 않은 리포트나 무효 화면은 닫고 고지로 간다. 대여가 열려 있거나 정차 중이 아니면 아무 일도 하지 않는다.
     */
    fun startFromNotification() {
        viewModelScope.launch(line) {
            _ui.first { !it.restoring }
            val isParked = withTimeoutOrNull(GEAR_WITHIN) { parked.catch { }.firstOrNull() } ?: _ui.value.parked
            if (!isParked) return@launch
            val state = _ui.value.state
            if (state == RentalState.RETURNED || state == RentalState.VOIDED) closeSession()
            if (move(RentalEvent.StartPickup)) say(Voice.notice())
        }
    }

    fun acceptNotice() = act {
        if (move(RentalEvent.AcceptNotice)) capturePickup()
    }

    fun startReturnCheck() = act {
        val ui = _ui.value
        if (!ui.parked || ui.pickup == null) return@act
        beginReturnCheck()
    }

    private fun beginReturnCheck() {
        // 앞 자막을 지운다. 같은 문구로 다시 말해도 이 화면의 자막으로 보이게 한다.
        if (move(RentalEvent.StartReturnCheck) { copy(check = emptyList(), checkReady = false, energy = null, returnTrip = null, arrived = false, caption = null) }) {
            trip?.clearArrival()
            previousCheck = null
            watchReturnCheck()
        }
    }

    /**
     * 화면을 여는 Gleo 함수. 앱을 막 켰으면 복원을 기다린다(버튼과 달리 버리면 답을 보내지 못한다). 앱이 뒤에 있었으면 기어를 듣지 않았으니 새로 읽는다.
     * 어느 앱이든 부를 수 있으므로 할 수 있는 일은 반납 체크 시작과 인수 기록 보기뿐이다. 둘 다 되돌릴 수 있고, 값은 받지 않는다.
     */
    fun onGleo(function: GleoFunction, rawParam: String?) {
        viewModelScope.launch(line) {
            _ui.first { !it.restoring }
            onGleoReply(if (GleoCommands.validParameters(rawParam)) decide(function) else GleoCommands.invalidParameters(function))
        }
    }

    private suspend fun decide(function: GleoFunction): GleoReply {
        val isParked = withTimeoutOrNull(GEAR_WITHIN) { parked.catch { }.firstOrNull() } ?: _ui.value.parked
        _ui.update { it.copy(parked = isParked) }
        val ui = _ui.value
        val damaged = ui.damaged != null || (ui.state == RentalState.IN_RENTAL && ui.pickup == null)
        return when (function) {
            GleoFunction.START_RETURN_CHECK -> GleoCommands.startReturnCheck(ui.state, isParked, damaged).also { if (it.act) beginReturnCheck() }.reply
            GleoFunction.SHOW_RECORD -> GleoCommands.showRecord(ui.state, isParked, damaged)
                .also { decision -> if (decision.act) _ui.update { it.copy(recordRequests = it.recordRequests + 1) } }.reply
            // 화면 없이 답한다(GleoDesk). 액티비티로 오면 받지 않는다.
            GleoFunction.WHAT_TO_CHECK -> GleoCommands.invalidParameters(function)
        }
    }

    /** V-04 '반납하러 가기'. 도착 예상 배터리를 받으면 비교를 보여 주고, 받지 못하면 경로 안내만 한다. */
    fun planReturnTrip() = act {
        val ui = _ui.value
        val pickup = ui.pickup
        // 경로 안내 중에도 다시 부를 수 있다. 내비가 죽어 경로가 없어져도 알림이 오지 않기 때문이다.
        val deciding = ui.returnTrip == ReturnTripStep.Estimating || ui.returnTrip is ReturnTripStep.Planned
        if (ui.state != RentalState.IN_RENTAL || !ui.parked || pickup == null || ui.damaged != null || ui.arrived || deciding) return@act
        _ui.update { it.copy(returnTrip = ReturnTripStep.Estimating) }
        val percent = attempt { trip?.estimateArrivalPercent() }
        // 기다리는 동안 반납 체크를 시작했으면 버린다.
        if (_ui.value.returnTrip != ReturnTripStep.Estimating) return@act
        val plan = ReturnTrip.plan(pickup.state, percent, settings.policy)
        if (plan != null) _ui.update { it.copy(returnTrip = ReturnTripStep.Planned(plan)) } else startRoute(null)
    }

    fun startReturnRoute() = act {
        val step = _ui.value.returnTrip as? ReturnTripStep.Planned ?: return@act
        startRoute(step.plan)
    }

    fun showNearbyChargers() = act {
        if (_ui.value.state == RentalState.IN_RENTAL) trip?.showNearbyChargers()
    }

    fun closeReturnPlan() = act {
        if (_ui.value.returnTrip is ReturnTripStep.Planned) _ui.update { it.copy(returnTrip = null) }
    }

    /** 직원 화면의 '도착 재현'. 에뮬레이터 시연용이고 실차에서는 [ReturnTripKeeper]가 받지 않는다. */
    fun simulateArrival() = act { trip?.simulateArrival() }

    fun cancelReturnCheck() = act {
        if (move(RentalEvent.CancelReturnCheck) { copy(check = emptyList(), checkReady = false, energy = null, caption = null) }) stopReturnCheck()
    }

    // 첫 판정 전에는 무엇이 남았는지 모르므로 받지 않는다. 앞 화면의 버튼을 두 번 눌러도 기록이 끝나 버리면 안 된다.
    fun confirmReturn() = act {
        if (_ui.value.checkReady && move(RentalEvent.ConfirmReturn)) {
            stopReturnCheck()
            captureReturn()
        }
    }

    /**
     * 고객이 차를 두고 떠났을 때 직원이 반납 체크 없이 남기는 반납 기록. 리포트에 직원 기록이라고 남는다([RentalReport.recordedBy]).
     * 정차 중에만 받고, 믿을 수 없는 기록은 이어 가지 않는다. PIN은 직원 화면이 [checkStaffPin]으로 먼저 확인한다.
     */
    fun recordStaffReturn() = act {
        val ui = _ui.value
        if (!ui.parked || ui.pickup == null || ui.damaged != null) return@act
        if (move(RentalEvent.StaffReturn) { copy(check = emptyList(), checkReady = false, energy = null, caption = null, returnTrip = null, arrived = false) }) {
            stopReturnCheck()
            trip?.clearArrival()
            captureReturn(RentalReport.STAFF)
        }
    }

    /** 무효와 직원 반납 기록 앞에서 직원 화면이 부른다. 다섯 번 틀리면 30초 동안 맞는 PIN도 받지 않는다. 화면을 닫았다 열어도 풀리지 않게 여기서 센다. */
    fun checkStaffPin(pin: String): Boolean {
        if (staffPinLockedMillis() > 0) return false
        if (pin == settings.staffPin) {
            pinWrong = 0
            return true
        }
        pinWrong++
        if (pinWrong >= PIN_TRIES) {
            pinWrong = 0
            pinLockedUntil = now().plusMillis(PIN_LOCK_MILLIS)
        }
        return false
    }

    /** PIN을 받지 않는 남은 시간(밀리초). 잠겨 있지 않으면 0이다. */
    fun staffPinLockedMillis(): Long =
        pinLockedUntil?.let { java.time.Duration.between(now(), it).toMillis().coerceAtLeast(0) } ?: 0

    fun close() = act { closeSession() }

    private fun closeSession() {
        if (move(RentalEvent.Close)) {
            session = null
            // 남은 길안내를 취소하면 내비가 스스로 화면 앞으로 나와 대기 화면을 가린다(취소 0.1초 뒤, 2026.11.02 에뮬레이터). 고객이 보던 앱을 앞으로 되돌릴 수 있게 표시해 둔다.
            frontUntil = if (visible && trip?.guiding == true) now().plusMillis(FRONT_WITHIN_MILLIS) else null
            trip?.close()
            onClosed()
            _ui.update { it.fresh() }
        }
    }

    /**
     * 화면이 가려질 때 MainActivity가 묻는다. 방금 닫으며 취소한 길안내 때문에 내비가 앞으로 나온 것이면 true이고, 앱을 다시 앞으로 가져온다. 한 번만 true다.
     * 고객이 그 뒤 직접 다른 앱으로 가는 것은 막지 않는다.
     */
    fun reclaimFront(): Boolean {
        val until = frontUntil ?: return false
        frontUntil = null
        return now() < until
    }

    /** 직원만 쓴다(기능 정의서 V-07). 무효 세션에는 리포트를 만들지 않는다. 저장하지 못하면 무효로 넘어가지 않는다. */
    fun void(reason: String) = act {
        val event = RentalEvent.Void(reason)
        if (_ui.value.state.next(event) == null) return@act
        val saved = saved { session?.let { store.markVoid(it.id, reason, now()) } } && persist(RentalState.VOIDED)
        if (!saved) return@act showError(SAVE_FAILED)
        stopReturnCheck()
        timeline.stop()
        onRecording(false)
        closeRentalHelpers()
        move(event, save = false) { copy(check = emptyList(), checkReady = false, energy = null, caption = null, damaged = null, returnTrip = null, arrived = false) }
    }

    /**
     * 화면이 보이는지 알린다. 보이지 않는 동안에는 반납 체크의 주기 확인과 리스너, 기어 구독을 멈추고, 다시 보이면 이어 간다(F-RET-04).
     * 대여 중 타임라인은 멈추지 않는다.
     */
    fun setVisible(shown: Boolean) {
        visible = shown
        trip?.setScreenVisible(shown)
        if (!shown) {
            // 다른 앱으로 가면 직원 화면을 떠난 것으로 본다.
            lockRecords()
            parkedJob?.cancel()
            parkedJob = null
        } else if (parkedJob == null) {
            watchParked()
        }
        viewModelScope.launch(line) {
            if (_ui.value.state != RentalState.RETURN_CHECK) return@launch
            if (!visible) {
                checkJob?.cancel()
                checkJob = null
                replyJob?.cancel()
            } else if (checkJob == null) {
                watchReturnCheck()
            }
        }
    }

    /**
     * 직원 PIN이 맞으면 기록 목록을 연다. 차에 탄 사람이면 누구나 직원 화면을 열 수 있으므로 지난 대여의 기록과 QR은 PIN 뒤에 둔다. 다음 고객이 앞 고객의 기록을 받으면 안 된다.
     * 대여 중 화면의 '인수 기록 보기'는 지금 대여의 것이라 여기에 들지 않는다. 틀린 횟수와 잠금은 [checkStaffPin]이 센다.
     */
    fun unlockRecords(pin: String): Boolean {
        if (!checkStaffPin(pin)) return false
        _ui.update { it.copy(recordsUnlocked = true) }
        loadRecords()
        return true
    }

    /** 기록 목록을 잠그고 읽어 둔 것을 비운다. 직원 화면을 떠나거나 앱이 뒤로 갈 때 부른다. */
    fun lockRecords() {
        _ui.update { it.copy(recordsUnlocked = false, records = emptyList(), declines = emptyList(), opened = null) }
    }

    /** 직원 화면을 떠날 때. 다음에 열 때 PIN을 다시 받고 진단을 새로 읽는다. */
    fun leaveStaffPanel() {
        lockRecords()
        _ui.update { it.copy(diagnosis = null) }
    }

    /** 직원 화면이 보이는 동안 부른다. 차를 읽기만 하고 아무것도 저장하지 않는다. */
    fun readDiagnosis() = act {
        val state = attempt { vehicle.state() } ?: return@act
        _ui.update { it.copy(diagnosis = Diagnostics.of(state, vehicle.lastCallbacks(), now())) }
    }

    /** 시연 콘솔의 점검이 읽는 한 줄(key=value). 개인정보가 없는 차와 앱의 상태만 적는다. */
    fun preflightLine(): String {
        val ui = _ui.value
        val rows = ui.diagnosis
        val failed = rows?.rows.orEmpty().filter { it.support == ItemSupport.FAILED }.joinToString(",") { it.item.key }
        val counts = rows?.let { "${it.count(ItemSupport.SUPPORTED)}/${it.count(ItemSupport.UNSUPPORTED)}/${it.count(ItemSupport.FAILED)}" } ?: "0/0/0"
        return "state=${ui.state} pickup=${ui.pickup != null} damaged=${ui.damaged != null} navi=${ui.naviReady} sdk=$counts failed=$failed"
    }

    // 직원 화면의 저장 기록 목록을 읽는다. 기간이 지난 기록을 먼저 지워 보이지 않게 한다.
    private fun loadRecords() = act {
        if (!_ui.value.recordsUnlocked) return@act
        attempt { store.purgeExpired(now()) }
        val records = attempt { store.sessions() }
        val declines = attempt { store.declines() }.orEmpty()
        if (records == null) return@act showError(READ_RECORD_FAILED)
        _ui.update { if (it.recordsUnlocked) it.copy(records = records, declines = declines) else it }
    }

    /** 저장된 기록으로 QR을 다시 만든다. 닫은 대여도 된다. 리포트가 있으면 리포트를, 없으면 인수 기록을 연다. 직원 PIN으로 목록을 연 동안만 받는다. */
    fun openRecord(sessionId: String) = act {
        if (!_ui.value.recordsUnlocked) return@act
        val opened = when (val report = store.readReport(sessionId)) {
            is Stored.Found -> report.value.let { SharedRecord(it.recordNo, it.hash, shareUrl { ShareCode.url(settings.reportBaseUrl, it) }) }
            is Stored.Damaged -> null
            Stored.Missing -> (store.readPickup(sessionId) as? Stored.Found)?.value
                ?.let { SharedRecord(it.recordNo, it.hash, shareUrl { ShareCode.url(settings.reportBaseUrl, it) }) }
        }
        if (opened != null) _ui.update { if (it.recordsUnlocked) it.copy(opened = opened) else it } else showError(READ_RECORD_FAILED)
    }

    fun closeRecord() = act { _ui.update { it.copy(opened = null) } }

    private suspend fun capturePickup() {
        _ui.update { it.copy(busy = true) }
        val today = now().atZone(SEOUL).toLocalDate()
        val code = attempt { vehicle.vin() }?.takeLast(4) ?: settings.vehicleCode
        val recordNo = attempt { RecordNumber.of(code, today, store.nextSequence(today)) }
        if (recordNo == null) {
            move(RentalEvent.PickupFailed) { copy(busy = false, error = SAVE_FAILED) }
            return
        }
        val record = attempt { captureWithRetry(UUID.randomUUID().toString(), recordNo, SnapshotKind.PICKUP) }
        if (record == null) {
            move(RentalEvent.PickupFailed) { copy(busy = false, error = PICKUP_READ_FAILED) }
            return
        }
        session = OpenSession(record.sessionId, record.recordNo)
        // 고객이 QR을 받은 뒤에 앱이 꺼져도 이어 갈 수 있게 저장을 마친 뒤에 보여 준다.
        if (!saved { store.save(record) } || !persist(RentalState.IN_RENTAL)) {
            session = null
            move(RentalEvent.PickupFailed) { copy(busy = false, error = SAVE_FAILED) }
            return
        }
        timeline.begin(record)
        onRecording(true)
        openRentalHelpers()
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, record) }
        move(RentalEvent.PickupSaved, save = false) { copy(busy = false, pickup = record, pickupUrl = url) }
        say(Voice.pickupDone(record.state))
    }

    private suspend fun captureReturn(recordedBy: String? = null) {
        val pickup = _ui.value.pickup ?: return
        _ui.update { it.copy(busy = true) }
        val record = attempt { captureWithRetry(pickup.sessionId, pickup.recordNo, SnapshotKind.RETURN) }
            ?: return returnFailed("차량 정보를 읽지 못했습니다. 다시 시도해 주세요.")
        // 저장에 실패하면 반납 체크로 돌아가므로 타임라인은 저장을 마칠 때까지 멈추지 않는다.
        val report = attempt { RentalReport.build(pickup, record, settings.policy, timeline.events, timeline.gaps, recordedBy) }
            ?: return returnFailed(SAVE_FAILED)
        if (!saved { store.saveReturn(record, report) } || !persist(RentalState.RETURNED)) return returnFailed(SAVE_FAILED)
        timeline.stop()
        onRecording(false)
        closeRentalHelpers()
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, report) }
        move(RentalEvent.ReturnSaved, save = false) { copy(busy = false, check = emptyList(), report = report, reportUrl = url) }
        say(Voice.returnDone())
    }

    private fun returnFailed(message: String) {
        move(RentalEvent.ReturnFailed) { copy(busy = false, error = message) }
        watchReturnCheck()
    }

    // 처음 한 번은 전체를 요약해 말하고, 그 뒤로는 고객이 고친 항목만 짧게 알린다(F-RET-02, F-RET-04).
    // 고침 안내(T-05)는 말하던 요약을 끊고 바로 말한다. 한 번에 여럿을 고쳤으면 첫 안내만 끊어 서로 지우지 않게 한다.
    private fun watchReturnCheck() {
        val pickup = _ui.value.pickup ?: return
        checkJob?.cancel()
        checkJob = null
        if (!visible) return
        checkJob = viewModelScope.launch(line) {
            ReturnCheck.readings(vehicle, pickup.state, settings.policy).collect { reading ->
                val items = reading.items
                val before = previousCheck
                var asked = false
                if (before == null) {
                    val text = Voice.returnCheck(items)
                    say(text)
                    asked = text.endsWith(Voice.RECORD_QUESTION)
                } else if (before != items) {
                    var flush = true
                    (before - items.toSet()).forEach { done ->
                        Voice.resolved(done, items)?.let {
                            say(it, flush)
                            flush = false
                            asked = asked || it.endsWith(Voice.RECORD_QUESTION)
                        }
                    }
                    (items - before.toSet()).forEach { new -> Voice.appeared(new, items)?.let { say(it) } }
                }
                previousCheck = items
                _ui.update { it.copy(check = items, energy = reading.energy, checkReady = true) }
                // 고칠 것이 새로 생기면 질문이 없어진 것이니 듣기를 멈춘다.
                if (before != items) {
                    if (items.any { it.actionable }) replyJob?.cancel() else if (asked) listenForReply()
                }
            }
        }
    }

    // 내비와 음성 인식은 대여가 열려 있는 동안만 띄운다. 인식 엔진은 처음 올릴 때 20초쯤 걸려 반납 체크 때 띄우면 5초 듣기에 늦는다(2026.11.02 에뮬레이터). 마이크는 질문 뒤에만 켠다.
    private fun openRentalHelpers() {
        trip?.open()
        replies?.warmUp()
    }

    // 반납 기록을 남겼거나 무효 처리했을 때. 내비는 고객이 화면을 닫을 때 끊는다([closeSession]). 이 앱이 건 길안내를 취소하면 내비가 화면 앞으로 나와(2026.11.02 에뮬레이터) 리포트의 QR을 가린다.
    private fun closeRentalHelpers() {
        trip?.clearArrival()
        replies?.release()
    }

    private fun startRoute(plan: ReturnPlan?) {
        trip?.startRoute()
        _ui.update { it.copy(returnTrip = ReturnTripStep.Guiding(plan, settings.returnPoint.name)) }
    }

    // 도착은 프로세스 수준에서 받아 말한다. 화면은 그 결과만 보여 준다. 저장하지 않으므로 앱이 꺼졌다 켜지면 사라진다.
    private fun watchReturnTrip(keeper: ReturnTripKeeper) {
        viewModelScope.launch { keeper.navigationReady.collect { ready -> _ui.update { it.copy(naviReady = ready) } } }
        viewModelScope.launch {
            keeper.arrived.collect { arrived ->
                _ui.update {
                    if (arrived) it.copy(arrived = true, returnTrip = null, caption = Voice.arrived()) else it.copy(arrived = false)
                }
            }
        }
        viewModelScope.launch { keeper.routeCancelled.collect { _ui.update { it.copy(returnTrip = null) } } }
        viewModelScope.launch { keeper.leaving.collect { text -> _ui.update { it.copy(caption = text) } } }
        viewModelScope.launch {
            keeper.routeFailed.collect { _ui.update { it.copy(returnTrip = null, caption = ReturnTrip.ROUTE_FAILED) } }
        }
    }

    /**
     * '반납 기록을 남길까요?'의 답을 한 번 듣는다(기능 정의서 F-VOI-03). 질문이 화면에 있고, 안내가 끝난 뒤에만, [REPLY_WITHIN] 동안 듣는다.
     * 자기 목소리를 듣지 않게 말이 끝나기를 기다리고, 말이 끝나지 않거나 통화 때문에 질문을 말하지 못했으면 듣지 않는다(통화 상대에게 한 '네'로 기록이 확정되면 안 된다).
     * 버튼은 그대로 있고, 뜻이 분명한 '네'만 버튼과 같은 일을 한다. 들은 문장은 판단하고 버린다.
     */
    private fun listenForReply() {
        val listener = replies ?: return
        replyJob?.cancel()
        replyJob = viewModelScope.launch(line) {
            withTimeoutOrNull(SPEECH_END_WITHIN) { voice.speaking.first { !it } } ?: return@launch
            if (voice.interrupted || !questionOpen()) return@launch
            _ui.update { it.copy(listening = true) }
            val heard = try {
                attempt { listener.listen(REPLY_WITHIN) }
            } finally {
                _ui.update { it.copy(listening = false) }
            }
            if (SpokenReplies.parse(heard) == SpokenReply.YES && questionOpen()) confirmReturn()
        }
    }

    private fun questionOpen(): Boolean {
        val ui = _ui.value
        return visible && ui.parked && ui.state == RentalState.RETURN_CHECK && ui.checkReady && ui.check.none { it.actionable }
    }

    private fun stopReturnCheck() {
        checkJob?.cancel()
        checkJob = null
        previousCheck = null
        replyJob?.cancel()
        replyJob = null
    }

    // 정차가 풀리면 말을 멈추고, 반납 체크 중이면 대여 중으로 돌아간다(F-RET-03, 요구사항 SAF-02).
    private fun leftPark() {
        voice.stop()
        if (move(RentalEvent.CancelReturnCheck) { copy(check = emptyList(), checkReady = false, energy = null, caption = null) }) stopReturnCheck()
        // 비교는 지금 배터리로 계산한 것이고 주행 중에는 묻지 않는다. 경로 안내 중이라는 표시는 남긴다.
        _ui.update { if (it.returnTrip is ReturnTripStep.Guiding) it else it.copy(returnTrip = null) }
    }

    // 항목별로 판단한다. 시연 모드에는 시나리오 값이 늘 있어서 '하나라도 읽었는지'로 보면 재시도가 돌지 않는다(F-SNP-01).
    // 실패한 항목만 새 값으로 채우고 이미 읽은 값은 버리지 않는다. 지원하지 않는 항목은 다시 읽어도 소용없다.
    // 읽기 한 번이 시간 제한까지 걸리면 PER-01(10초) 안에 끝나도록 시도를 줄인다. 하나도 읽지 못하면 null이다.
    private suspend fun captureWithRetry(sessionId: String, recordNo: String, kind: SnapshotKind): SnapshotRecord? {
        val started = now()
        var record = recorder.capture(sessionId, recordNo, kind)
        var retries = 0
        while (retries < READ_RETRIES) {
            val failed = Rental.RECORDED.filter { record.state.missing(it) is Unavailable.Failed }.toSet()
            if (failed.isEmpty() || java.time.Duration.between(started, now()) > RETRY_WITHIN) break
            delay(RETRY_EVERY)
            record = record.copy(state = record.state.overlay(vehicle.state().forRecord(Rental.RECORDED), failed))
            retries++
        }
        return record.takeIf { Rental.RECORDED.any(it.state::has) }
    }

    // 앱을 켰을 때. 대여가 어디까지 갔는지 읽어 화면을 맞추고, 끝나면 입력을 받는다. 음성과 자막은 내지 않는다.
    private suspend fun restore() {
        val change = attempt {
            timeline.removeUnfinishedWrites()
            when (val saved = store.readSession()) {
                Stored.Missing -> IDLE_SCREEN
                is Stored.Damaged -> damaged()
                is Stored.Found -> restore(saved.value)
            }
        } ?: damaged()
        _ui.update { it.change().copy(restoring = false) }
        refreshLog()
        // 복원을 마친 뒤에 지운다. 지우다 실패해도 대여를 이어 가는 데는 지장이 없다.
        attempt { store.purgeExpired(now()) }
    }

    // 건수는 리포트와 같은 계산으로 센다. 인수 기록이 없으면 셀 것이 없다.
    private fun refreshLog() {
        val pickup = _ui.value.pickup ?: return
        val events = timeline.events.orEmpty()
        val log = RentalLog(Rental.timeline(pickup.state, events).size, Rental.gaps(events, timeline.gaps, now()).size)
        _ui.update { it.copy(log = log) }
    }

    private suspend fun restore(saved: SavedSession): RentalUi.() -> RentalUi {
        session = saved.sessionId?.let { OpenSession(it, saved.recordNo.orEmpty()) }
        return when (saved.state) {
            RentalState.IDLE -> IDLE_SCREEN
            RentalState.NOTICE, RentalState.PICKUP -> {
                session = null
                persist(RentalState.IDLE)
                IDLE_SCREEN
            }
            RentalState.IN_RENTAL -> restoreRental()
            RentalState.RETURN_CHECK, RentalState.RETURN -> restoreRental().also { persist(RentalState.IN_RENTAL) }
            RentalState.RETURNED -> restoreReport(saved)
            RentalState.VOIDED -> {
                val pickup = session?.let { (store.readPickup(it.id) as? Stored.Found)?.value }
                val url = pickup?.let { shareUrl { ShareCode.url(settings.reportBaseUrl, it) } }
                return { copy(state = RentalState.VOIDED, pickup = pickup, pickupUrl = url) }
            }
        }
    }

    private suspend fun restoreRental(): RentalUi.() -> RentalUi {
        val id = session?.id ?: return damaged()
        val pickup = (store.readPickup(id) as? Stored.Found)?.value ?: return damaged()
        timeline.resume(pickup)
        onRecording(true)
        openRentalHelpers()
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, pickup) }
        return { copy(state = RentalState.IN_RENTAL, restored = true, pickup = pickup, pickupUrl = url) }
    }

    // 닫지 않고 차를 끈 리포트는 다음 고객이 차를 켤 때 앞 고객의 기록과 QR을 보여 줄 수 있다. 화면이 스스로 닫히는 시간([REPORT_SHOWN_FOR])이 지났으면 닫고 시작한다. 기록은 직원 목록에 남아 있다.
    private fun restoreReport(saved: SavedSession): RentalUi.() -> RentalUi {
        val id = session?.id ?: return damaged(RentalState.RETURNED)
        val report = (store.readReport(id) as? Stored.Found)?.value ?: return damaged(RentalState.RETURNED)
        val age = java.time.Duration.between(saved.at, now())
        if (age.isNegative || age > REPORT_SHOWN_FOR) {
            session = null
            persist(RentalState.IDLE)
            return IDLE_SCREEN
        }
        val url = shareUrl { ShareCode.url(settings.reportBaseUrl, report) }
        return { copy(state = RentalState.RETURNED, pickup = report.pickup, report = report, reportUrl = url) }
    }

    // 대여가 열려 있는지조차 믿을 수 없으면 대여 중으로 본다. 새 인수는 전이표가 막고, 직원 무효 처리로만 풀린다.
    private fun damaged(state: RentalState = RentalState.IN_RENTAL): RentalUi.() -> RentalUi =
        { copy(state = state, damaged = RECORD_DAMAGED) }

    // 복원이 끝나기 전의 입력은 버린다. 복원 뒤로 미루면 고객이 보지 못한 화면의 버튼이 눌린다.
    private fun act(block: suspend () -> Unit) {
        if (_ui.value.restoring) return
        viewModelScope.launch(line) { block() }
    }

    // 상태와 그 화면이 보여 줄 값을 한 번에 바꾼다. 나눠서 바꾸면 그 사이 프레임에 빈 화면이 비친다.
    // 이전 상태의 안내는 끊는다. 저장을 먼저 마친 전이는 save를 false로 부른다.
    private fun move(event: RentalEvent, save: Boolean = true, change: RentalUi.() -> RentalUi = { this }): Boolean {
        val next = _ui.value.state.next(event) ?: return false
        val saved = !save || persist(next)
        voice.stop()
        _ui.update { it.copy(state = next, error = if (saved) null else SAVE_FAILED).change() }
        return true
    }

    private fun persist(state: RentalState): Boolean = saved {
        val open = session.takeIf { state != RentalState.IDLE }
        store.saveSession(SavedSession(state, open?.id, open?.recordNo, now()))
    }

    private fun say(text: String, flush: Boolean = false) {
        _ui.update { it.copy(caption = text) }
        // 주행 중에는 말하지 않는다(요구사항 SAF-02). 자막은 남긴다.
        if (_ui.value.parked) voice.say(text, flush)
    }

    // 대기 화면으로 돌아갈 때. 그 대여의 값은 지우고 차와 정차, 오류 표시만 남긴다.
    private fun RentalUi.fresh() = RentalUi(demo = demo, vehicle = vehicle, vinRead = vinRead, parked = parked, error = error)

    private fun showError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    // 요약본으로도 QR 한도(1,000B)를 넘으면 링크를 만들지 않는다. 화면은 QR 자리에 직원 안내를 보여 준다.
    private fun shareUrl(make: () -> String): String? = runCatching(make).getOrNull()

    // 디스크 오류로 앱이 죽으면 안 된다. 실패하면 false이고, 부르는 쪽이 화면에 알린다.
    private inline fun saved(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        false
    }

    // 차 읽기와 저장소의 실패로 앱이 죽으면 안 된다. 코루틴 취소만 그대로 올린다.
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override fun onCleared() {
        voice.stop()
    }

    private class OpenSession(val id: String, val recordNo: String)

    internal companion object {
        // 원인·책임을 판단하지 않는다. '손상' 같은 금지어(Wording) 없이 사실만 적는다.
        const val PICKUP_READ_FAILED = "차량 정보를 읽지 못했습니다. 직원에게 알려 주세요."
        const val SAVE_FAILED = "기록을 저장하지 못했습니다. 직원에게 알려 주세요."
        const val RECORD_DAMAGED = "저장된 기록이 무결성 값과 맞지 않아 이어 갈 수 없습니다. 직원에게 알려 주세요."
        const val READ_RECORD_FAILED = "저장된 기록을 읽지 못했습니다."
        const val DECLINED_CAPTION = "업체 안내에 따라 점검해 주세요."

        private val GEAR_WITHIN = 1.5.seconds
        private val REPLY_WITHIN = 5.seconds

        // 안내 15초에 음성 모델을 처음 올리는 시간(에뮬레이터 25초)을 더한 값이다.
        private val SPEECH_END_WITHIN = 45.seconds
        private const val PIN_TRIES = 5
        private const val PIN_LOCK_MILLIS = 30_000L
        private const val READ_RETRIES = 3
        private val RETRY_EVERY = 1.seconds
        private val RETRY_WITHIN = 5.seconds.toJavaDuration()
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        // 길안내 취소 뒤 내비가 앞으로 나오기까지 기다리는 시간. 에뮬레이터는 0.1초쯤이고, 차가 바쁠 때를 생각해 넉넉히 잡는다.
        private const val FRONT_WITHIN_MILLIS = 5_000L

        // 입력이 없으면 리포트의 QR을 3분 뒤에 내리고 다시 3분 뒤에 닫는다(ReportView). 앱을 다시 켰을 때도 같은 시간을 쓴다.
        private val REPORT_SHOWN_FOR = java.time.Duration.ofMinutes(6)
        private val IDLE_SCREEN: RentalUi.() -> RentalUi = { this }
    }
}
