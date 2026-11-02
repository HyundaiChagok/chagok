package app.chagok.rental

import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.VehicleEvent
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.RecordGap
import app.chagok.core.rental.Rental
import app.chagok.core.rental.TimelineFold
import app.chagok.core.rental.TimelineLine
import app.chagok.core.session.ActiveSession
import app.chagok.core.session.SessionRecorder
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * 대여 중 타임라인을 쌓고 바로 저장한다(기능 정의서 F-TML-01·02, F-SES-03). 앱이 꺼져도 사진이 남기지 못하는 값이 남고,
 * 앱이 꺼져 있던 구간은 '변화 없음'이 아니라 기록 공백으로 남는다. 소리는 내지 않는다.
 *
 * 화면 생명주기와 무관하게 프로세스에 하나만 둔다(AppGraph). 화면([RentalController])과 서비스([RecordingService])가 같은 것을 부르고,
 * 이미 따라가는 대여를 다시 이어 달라고 하면 아무것도 하지 않으므로 같은 기록에 공백이나 이벤트가 이중으로 쓰이지 않는다.
 *
 * 공백을 알려면 앱이 언제까지 살아 있었는지 알아야 해서 [ALIVE_EVERY]마다 생존 시각을 적는다.
 * 다른 앱이 화면에 떠 있어도 이어 간다(요구사항 PER-04). 차에서는 [Rental.WATCHED_DURING_RENTAL]만 구독한다.
 * [scope]의 디스패처는 저장소 쓰기를 해도 되는 곳이어야 한다.
 */
class TimelineKeeper(
    private val vehicle: VehicleSource,
    private val store: RecordStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
) {
    private val lock = Mutex()

    @Volatile
    private var session: ActiveSession? = null

    @Volatile
    private var followingId: String? = null

    private var beat: Job? = null
    private val recordedGaps = CopyOnWriteArrayList<RecordGap>()

    private val _saveFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 저장하지 못했을 때마다 알린다. 대여는 이어 가고(메모리에는 남아 있다), 화면이 떠 있으면 오류로 보여 준다. */
    val saveFailed: SharedFlow<Unit> = _saveFailed

    private val _updates = MutableStateFlow(0)

    /** 이벤트나 공백이 늘 때마다 올라간다. 화면이 지금까지의 건수를 다시 세는 신호다. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    /** 지금까지의 이벤트. 저장해 둔 것과 이번에 받은 것을 합친 것이고, 이어 가는 중이 아니면 null이다. */
    val events: List<LoggedEvent>? get() = session?.events?.value

    /** 앱이 꺼져 있었거나 절전으로 멈췄던 구간. 구독이 끊긴 구간은 [events]에 들어 있다. */
    val gaps: List<RecordGap> get() = recordedGaps.toList()

    /** 방금 남긴 인수 기록에서 시작한다. */
    suspend fun begin(pickup: SnapshotRecord) = lock.withLock {
        if (followingId == pickup.sessionId) return@withLock
        stopFollowing()
        saving { store.saveAlive(pickup.sessionId, pickup.capturedAt) }
        follow(pickup, emptyList())
    }

    /**
     * 프로세스가 새로 떴을 때 이어 간다. 마지막으로 살아 있던 시각부터 지금까지를 공백으로 남긴다.
     * 이 프로세스에서 이미 따라가고 있으면(화면만 다시 열렸거나 서비스가 먼저 이어 갔으면) 아무것도 하지 않는다. 끊긴 적이 없으니 공백도 없다.
     */
    suspend fun resume(pickup: SnapshotRecord) = lock.withLock {
        if (followingId == pickup.sessionId) return@withLock
        stopFollowing()
        val id = pickup.sessionId
        val lines = store.readTimeline(id)
        val events = lines.filterIsInstance<TimelineLine.Event>().map { it.logged }
        recordedGaps += lines.filterIsInstance<TimelineLine.Gap>().map { it.gap }
        val startedAt = now()
        val lastSeen = listOfNotNull(
            store.readAlive(id), pickup.capturedAt, events.lastOrNull()?.at, recordedGaps.maxOfOrNull { it.to },
        ).max()
        if (lastSeen < startedAt) addGap(id, RecordGap(lastSeen, startedAt))
        saving { store.saveAlive(id, startedAt) }
        // 구독이 끊긴 채 꺼졌으면 여기서 닫는다. 그러지 않으면 반납 때까지 끊긴 것으로 남는다.
        val closed = if (cutOff(events)) {
            val resumed = LoggedEvent(startedAt, null, VehicleEvent.Note(VehicleEvent.SOURCE_RESUMED))
            saving { store.appendTimeline(id, TimelineLine.Event(resumed)) }
            events + resumed
        } else {
            events
        }
        follow(pickup, closed)
    }

    /** 저장된 대여가 아직 열려 있으면 이어 간다. 화면 없이 프로세스가 떴을 때(재부팅, 시스템 재시작) 서비스가 부른다. 이을 대여가 없으면 false다. */
    suspend fun resumeSaved(): Boolean {
        removeUnfinishedWrites()
        val saved = (store.readSession() as? Stored.Found)?.value ?: return false
        val id = saved.sessionId?.takeIf { saved.isOpen } ?: return false
        val pickup = (store.readPickup(id) as? Stored.Found)?.value ?: return false
        resume(pickup)
        return true
    }

    /** 반납 기록을 저장했거나 무효 처리한 뒤에 부른다. */
    suspend fun stop() = lock.withLock { stopFollowing() }

    /** 쓰다 꺼져 남은 임시 파일을 지운다. 이미 따라가는 대여가 있으면 그 쓰기를 건드리지 않게 건너뛴다. */
    suspend fun removeUnfinishedWrites() = lock.withLock {
        if (session == null) store.removeUnfinishedWrites()
    }

    private suspend fun stopFollowing() {
        beat?.cancel()
        beat = null
        session?.let { it.finish(it.start) }
        session = null
        followingId = null
        recordedGaps.clear()
    }

    private suspend fun follow(pickup: SnapshotRecord, restored: List<LoggedEvent>) {
        val id = pickup.sessionId
        val fold = TimelineFold(pickup.state)
        restored.forEach { fold.apply(it.event) }
        followingId = id
        val changed = { _updates.update { it + 1 } }
        session = SessionRecorder(vehicle, now = now).start(
            scope,
            initial = pickup.toSnapshot(),
            restored = restored,
            resubscribeAfter = RESUBSCRIBE_AFTER,
            items = Rental.WATCHED_DURING_RENTAL,
            onEvent = { logged ->
                saving { store.appendTimeline(id, TimelineLine.Event(logged)) }
                changed()
            },
            // 구독 직후 현재 값을 주거나 같은 값을 다시 주는 이벤트는 바뀐 것이 없으니 남기지 않는다.
            keep = { Rental.recordsDuringRental(it) && fold.apply(it).isNotEmpty() },
        )
        changed()
        beat = scope.launch {
            var last = now()
            while (true) {
                delay(ALIVE_EVERY)
                val at = now()
                // 차가 절전으로 잠들면 프로세스는 살아 있어도 그동안의 변화를 보지 못한다.
                if (java.time.Duration.between(last, at) > ALIVE_EVERY.toJavaDuration().multipliedBy(SLEPT_AFTER)) {
                    addGap(id, RecordGap(last, at))
                }
                saving { store.saveAlive(id, at) }
                last = at
            }
        }
    }

    private fun addGap(sessionId: String, gap: RecordGap) {
        recordedGaps += gap
        saving { store.appendTimeline(sessionId, TimelineLine.Gap(gap)) }
        _updates.update { it + 1 }
    }

    private fun cutOff(events: List<LoggedEvent>): Boolean {
        val last = events.lastOrNull { (it.event as? VehicleEvent.Note)?.type in SOURCE_NOTES } ?: return false
        return (last.event as VehicleEvent.Note).type == VehicleEvent.SOURCE_ERROR
    }

    // 저장하지 못해도 대여는 이어 간다. 메모리에는 남아 있고 화면에 알린다.
    private fun saving(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            _saveFailed.tryEmit(Unit)
        }
    }

    private companion object {
        val ALIVE_EVERY = 1.minutes
        val RESUBSCRIBE_AFTER = 1.minutes
        const val SLEPT_AFTER = 3L
        val SOURCE_NOTES = setOf(VehicleEvent.SOURCE_ERROR, VehicleEvent.SOURCE_RESUMED)
    }
}
