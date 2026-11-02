package app.chagok.rental

import android.util.Log
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ReturnPoint
import app.chagok.core.rental.Voice
import app.chagok.core.source.ReturnNavigation
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 반납 지점까지의 길안내와 도착(기능 정의서 F-RET-05, F-NAV-01). 길안내 중에는 내비가 화면에 떠 있으므로 화면 생명주기와 무관하게 프로세스에 하나만 둔다(AppGraph).
 *
 * 도착하면 음성(T-11)으로 반납 체크를 제안하고, 앱이 뒤에 있으면 알림으로 들어오게 한다. 도착 사실은 메모리에만 두고 저장하지 않는다. 앱이 꺼지면 사라지고 고객이 '반납 체크'를 누르면 된다.
 * 대여 중이 아니면(반납 체크 중, 닫은 뒤) 무시한다. 대여 상태는 저장소의 session.json을 본다. 화면이 없어도 알 수 있고, 상태가 바뀔 때마다 저장되는 값이다.
 *
 * 내비는 화면이 대여를 열거나 이어 갈 때 연결한다. 재부팅 뒤 서비스만 도는 동안에는 길안내를 요청한 적이 없으니 연결하지 않는다.
 * [canSimulate]는 직원 화면의 '도착 재현'을 받을지다. 실차에서는 false라 호출해도 아무 일도 하지 않는다.
 *
 * T-13: 도착한 뒤 반납 기록 없이 P단에서 운전석 문이 처음 열리면 내리려는 것으로 보고 한 번 말한다. 문은 도착한 뒤부터 그 말을 하거나 반납 체크가 시작될 때까지만 듣는다.
 * 대여 중에 문 열림을 남기지 않는 원칙은 그대로다. 들은 값은 판단에만 쓰고 어디에도 쓰지 않는다([vehicle]을 주지 않으면 이 안내는 없다).
 */
class ReturnTripKeeper(
    private val navi: ReturnNavigation?,
    private val store: RecordStore,
    private val voice: () -> VoiceOutput,
    private val parked: Flow<Boolean>,
    private val notice: ArrivalNotice,
    private val scope: CoroutineScope,
    private val point: ReturnPoint,
    val canSimulate: Boolean = false,
    private val vehicle: VehicleSource? = null,
) {
    /** 도착을 화면 밖으로 알리는 방법. Android 알림 구현은 [ArrivalNotification]이다. */
    interface ArrivalNotice {
        fun show()
        fun cancel()
    }

    private val _arrived = MutableStateFlow(false)

    /** 반납 지점 도착 이벤트를 받았고 고객이 아직 반납 체크를 시작하지 않았는지 */
    val arrived: StateFlow<Boolean> = _arrived.asStateFlow()

    val navigationReady: StateFlow<Boolean> = navi?.connected ?: MutableStateFlow(false)
    val routeCancelled: Flow<Unit> = navi?.routeCancelled ?: emptyFlow()
    val routeFailed: Flow<Unit> = navi?.routeFailed ?: emptyFlow()

    private val _leaving = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** T-13을 말할 때 그 문장이 온다. 화면이 떠 있으면 자막으로 보여 준다. */
    val leaving: SharedFlow<String> = _leaving

    @Volatile
    private var screenVisible = false
    private var arrivals: Job? = null
    private var doorWatch: Job? = null

    /** 대여가 열리거나 화면이 대여를 이어 갈 때 부른다. 이미 듣고 있으면 아무것도 하지 않는다. */
    fun open() {
        val source = navi ?: return
        synchronized(this) {
            if (arrivals == null) arrivals = scope.launch { source.arrivals.collect { arrive() } }
        }
        source.connect()
    }

    /** 이 앱이 건 길안내가 아직 끝나지 않았는지. [close]가 그것을 취소하면 내비가 화면 앞으로 나온다. */
    val guiding: Boolean get() = navi?.guiding == true

    /** 반납을 마쳤거나 무효 처리한 뒤에 부른다. */
    fun close() {
        synchronized(this) {
            arrivals?.cancel()
            arrivals = null
        }
        navi?.disconnect()
        clearArrival()
    }

    fun setScreenVisible(shown: Boolean) {
        screenVisible = shown
        if (shown) {
            notice.cancel()
            // 부팅 직후처럼 내비 서비스가 아직 없어 연결에 실패했으면 화면이 다시 뜰 때 다시 해 본다.
            if (synchronized(this) { arrivals != null }) navi?.connect()
        }
    }

    suspend fun estimateArrivalPercent(): Int? = navi?.estimateArrivalPercent(point)

    fun startRoute() {
        navi?.startRoute(point)
    }

    fun showNearbyChargers() {
        navi?.showNearbyChargers()
    }

    /**
     * 에뮬레이터 시연용. Virtual Console에 도착 트리거가 없어 직원 화면에서 진짜 이벤트와 같은 길로 넘긴다.
     * [SIMULATE_AFTER] 뒤에 넘겨서 그 사이 내비 화면으로 가면 앱이 뒤에 있을 때의 알림까지 볼 수 있다.
     *
     * 진짜 도착은 내비가 경로를 끝내지만 재현은 경로가 남는다. 앱이 뒤에 있으면 내비가 이미 앞에 있으니 여기서 취소해도 화면이 바뀌지 않는다.
     * 앱이 앞에 있으면 지금 취소하면 내비가 도착 안내를 가리므로 '닫기'까지 미룬다([RentalController]가 그때 앱을 다시 앞으로 가져온다).
     */
    fun simulateArrival() {
        if (!canSimulate) return
        scope.launch {
            delay(SIMULATE_AFTER)
            arrive()
            if (!screenVisible) navi?.cancelRoute()
        }
    }

    fun clearArrival() {
        _arrived.value = false
        synchronized(this) {
            doorWatch?.cancel()
            doorWatch = null
        }
        notice.cancel()
    }

    // 저장소를 읽다 실패해도 앱이 죽으면 안 된다. 도착을 못 알리면 고객이 '반납 체크'를 누르면 된다.
    private suspend fun arrive() {
        try {
            onArrived()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ChagokNavi", "도착을 처리하지 못했습니다: $e")
        }
    }

    private suspend fun onArrived() {
        val state = (store.readSession() as? Stored.Found)?.value?.state
        if (state != RentalState.IN_RENTAL || _arrived.value) return
        _arrived.value = true
        if (!screenVisible) notice.show()
        vehicle?.let { source -> synchronized(this) { doorWatch = scope.launch { watchDriverDoor(source) } } }
        // 주행 중에는 말하지 않는다(요구사항 SAF-02). 도착 이벤트는 차가 멈추기 전에 올 수 있어 P단이 될 때까지 기다린다. 기어를 읽지 못하면 말하지 않고 화면과 알림만 남는다.
        withTimeoutOrNull(SAY_WITHIN) { parked.first { it } } ?: return
        if (_arrived.value) voice().say(Voice.arrived())
    }

    // 주행 중에는 문을 읽지 못하는 차가 있어 P단이 된 뒤에 지금 문을 읽는다. 그때 읽지 못하면 언제 열렸는지 알 수 없으니 말하지 않는다.
    // 말하면 듣기를 끝낸다. first가 끝나면 문과 기어 구독이 함께 풀린다.
    private suspend fun watchDriverDoor(source: VehicleSource) {
        try {
            withTimeoutOrNull(SAY_WITHIN) { parked.first { it } } ?: return
            var open = source.state().doorsOpen[Area.FRONT_LEFT] ?: return
            var inPark = true
            merge(
                source.changes(setOf(StateItem.DOORS)).filterIsInstance<VehicleEvent.DoorChanged>().filter { it.area == Area.FRONT_LEFT },
                parked.catch { },
            ).first { signal ->
                val opened = signal is VehicleEvent.DoorChanged && signal.open && !open
                when (signal) {
                    is VehicleEvent.DoorChanged -> open = signal.open
                    is Boolean -> inPark = signal
                }
                opened && inPark
            }
            sayLeaving()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 문 구독이 끊기면 말하지 않는다. 고객은 화면의 '반납 체크 시작'을 그대로 볼 수 있다.
            Log.w("ChagokNavi", "문을 듣지 못했습니다: $e")
        }
    }

    private suspend fun sayLeaving() {
        val state = (store.readSession() as? Stored.Found)?.value?.state
        if (state != RentalState.IN_RENTAL || !_arrived.value) return
        val text = Voice.leaving()
        voice().say(text, flush = true)
        _leaving.tryEmit(text)
        if (!screenVisible) notice.show()
    }

    private companion object {
        val SAY_WITHIN = 5.minutes
        val SIMULATE_AFTER = 5.seconds
    }
}
