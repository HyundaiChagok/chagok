package app.chagok.core.session

import app.chagok.core.model.GeoPoint
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.source.LocationSource
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration

/**
 * 대여·맡김·시승처럼 "시작 → 진행 → 종료"로 끝나는 세션을 기록한다.
 *
 * 앱이 중간에 꺼져 이벤트가 끊겨도 시작·종료 스냅샷만 있으면 점검할 수 있도록,
 * 스냅샷은 [snapshot]으로 따로 찍을 수 있게 열어 둔다.
 */
class SessionRecorder(
    private val vehicle: VehicleSource,
    private val location: LocationSource? = null,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun snapshot(): VehicleSnapshot =
        VehicleSnapshot(now(), location?.current(), vehicle.state())

    /**
     * 구독을 먼저 시작한 뒤 시작 스냅샷을 찍는다. 세션은 [ActiveSession.finish]를 부를 때까지 [scope] 안에서 기록한다.
     * 방금 찍은 기록(예: 인수 기록)이 있으면 [initial]로 넘겨 차를 다시 읽지 않는다. 그 기록과 구독 시작 사이
     * 몇 ms 동안의 변화는 타임라인에 남지 않는다.
     * [keep]이 false인 차 이벤트는 남기지 않는다. 예: 렌터카는 문 열림 같은 고객 행동을 남기지 않는다.
     * [items]를 주면 그 항목만 구독해, 남기지 않을 값은 듣지도 않는다.
     *
     * 앱을 다시 켜 이어 가는 세션이면 저장해 둔 이벤트를 [restored]로 넘긴다. [onEvent]는 새 이벤트가 기록될 때마다
     * 불리므로 여기서 바로 저장한다. [resubscribeAfter]를 주면 구독이 끊겼을 때 그만큼 기다렸다가 다시 구독하고,
     * 다시 시작한 시각을 [VehicleEvent.SOURCE_RESUMED]로 남긴다.
     */
    suspend fun start(
        scope: CoroutineScope,
        initial: VehicleSnapshot? = null,
        restored: List<LoggedEvent> = emptyList(),
        resubscribeAfter: Duration? = null,
        items: Set<StateItem>? = null,
        onEvent: (LoggedEvent) -> Unit = {},
        keep: (VehicleEvent) -> Boolean = { true },
    ): ActiveSession {
        val log = EventLog(now, restored)
        val source = if (items != null) vehicle.changes(items) else vehicle.changes()
        val vehicleEvents = source.filter { keep(it) }.keepGoing(resubscribeAfter)
        val moves = location?.updates()?.map { VehicleEvent.Moved(it) }?.keepGoing() ?: emptyFlow()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            merge(vehicleEvents, moves).collect { onEvent(log.append(it)) }
        }
        val start = initial ?: snapshot()
        if (log.lastLocation == null) log.lastLocation = start.location
        return ActiveSession(start, log, job, this)
    }

    // 구독 하나가 끊겨도 세션은 이어 가고, 끊긴 사실은 기록에 남긴다.
    private fun Flow<VehicleEvent>.keepGoing(resubscribeAfter: Duration? = null): Flow<VehicleEvent> {
        fun cut(e: Throwable) = VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, e.message ?: e::class.simpleName.orEmpty())
        if (resubscribeAfter == null) return catch { e -> emit(cut(e)) }
        var waiting = false
        return onStart {
            if (waiting) emit(VehicleEvent.Note(VehicleEvent.SOURCE_RESUMED))
            waiting = false
        }.retryWhen { e, _ ->
            emit(cut(e))
            waiting = true
            delay(resubscribeAfter)
            true
        }
    }
}

class ActiveSession internal constructor(
    val start: VehicleSnapshot,
    private val log: EventLog,
    private val job: Job,
    private val recorder: SessionRecorder,
) {
    val events: StateFlow<List<LoggedEvent>> get() = log.events

    fun note(type: String, detail: String = "") {
        log.append(VehicleEvent.Note(type, detail))
    }

    /** 방금 찍은 종료 기록(예: 반납 기록)이 있으면 [end]로 넘겨 차를 다시 읽지 않는다. */
    suspend fun finish(end: VehicleSnapshot? = null): CompletedSession {
        job.cancelAndJoin()
        return CompletedSession(start, end ?: recorder.snapshot(), log.events.value)
    }
}

data class CompletedSession(
    val start: VehicleSnapshot,
    val end: VehicleSnapshot,
    val events: List<LoggedEvent>,
)

internal class EventLog(private val now: () -> Instant, restored: List<LoggedEvent> = emptyList()) {
    private val _events = MutableStateFlow(restored)
    val events: StateFlow<List<LoggedEvent>> = _events.asStateFlow()

    @Volatile
    var lastLocation: GeoPoint? = null

    fun append(event: VehicleEvent): LoggedEvent {
        if (event is VehicleEvent.Moved) lastLocation = event.point
        val logged = LoggedEvent(now(), lastLocation, event)
        _events.update { it + logged }
        return logged
    }
}
