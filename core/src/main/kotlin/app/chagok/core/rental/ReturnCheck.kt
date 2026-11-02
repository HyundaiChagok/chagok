package app.chagok.core.rental

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 반납 체크 화면의 한 줄(기능 정의서 F-RET-01). */
sealed interface CheckItem {
    /** 화면 왼쪽 칸 */
    val label: String

    /** 화면 오른쪽 칸 */
    val detail: String

    /** 음성으로 부를 이름. 예: "조수석 창문" */
    val subject: String

    /** 고객이 지금 고치거나 직접 확인할 것. 아니면 알려 주기만 한다. */
    val actionable: Boolean get() = true

    /** 차 안에서 바로 고칠 수 있는 것(창문·문·트렁크·충전구·라이트) */
    val fixable: Boolean get() = false

    data class WindowOpen(val area: Area) : CheckItem {
        override val label: String get() = "창문"
        override val detail: String get() = "${area.label} 열림"
        override val subject: String get() = "${area.label} 창문"
        override val fixable: Boolean get() = true
    }

    data class DoorOpen(val area: Area) : CheckItem {
        override val label: String get() = "문"
        override val detail: String get() = "${area.label} 열림"
        override val subject: String get() = "${area.label} 문"
        override val fixable: Boolean get() = true
    }

    /** item은 TRUNK, CHARGE_PORT 중 하나 */
    data class ClosureOpen(val item: StateItem) : CheckItem {
        override val label: String get() = item.label
        override val detail: String get() = "열림"
        override val subject: String get() = item.label
        override val fixable: Boolean get() = true
    }

    /** item은 HEADLIGHTS, TAIL_LAMP, CABIN_LIGHT 중 하나 */
    data class LightOn(val item: StateItem) : CheckItem {
        override val label: String get() = item.label
        override val detail: String get() = "켜짐"
        override val subject: String get() = item.label
        override val fixable: Boolean get() = true
    }

    /**
     * 고칠 수 있는 항목(문·창문·트렁크·충전구·라이트)을 읽지 못했다. 모르는 값을 문제없음으로 보지 않고 직접 확인해 달라고 한다.
     * 고칠 대상이 무엇인지 모르므로 [fixable]이 아니고, 확인할 것에는 든다.
     */
    data class Unreadable(val item: StateItem) : CheckItem {
        override val label: String get() = item.label
        override val detail: String get() = "읽지 못함"
        override val subject: String get() = item.label
    }

    /** 사람일 수도 있어 안내만 하고 기록하지 않는다. */
    data class SeatDetected(val area: Area) : CheckItem {
        override val label: String get() = "좌석"
        override val detail: String get() = "${area.label} 감지"
        override val subject: String get() = area.label
    }

    data class EnergyDrop(val item: StateItem, val before: Int, val after: Int) : CheckItem {
        override val label: String get() = item.label
        override val detail: String get() = "인수 $before% · 지금 $after%"
        override val subject: String get() = item.label
        override val actionable: Boolean get() = false
    }

    /** 원인은 말하지 않고 기록에 남긴다는 사실만 알린다. */
    data class NewTelltales(val telltales: Set<Telltale>) : CheckItem {
        override val label: String get() = "경고등"
        override val detail: String get() = "새로 켜짐 ${telltales.size} · 기록에 남김"
        override val subject: String get() = "경고등"
        override val actionable: Boolean get() = false
    }

    /** 인수 때 경고등을 읽지 못해 새로 켜졌는지 알 수 없다. '새로 켜짐'으로 말하면 고객에게 불리하므로 나눈다. */
    data class TelltalesUnverified(val telltales: Set<Telltale>) : CheckItem {
        override val label: String get() = "경고등"
        override val detail: String get() = "켜짐 ${telltales.size} · 인수 때 확인 못 함"
        override val subject: String get() = "경고등"
        override val actionable: Boolean get() = false
    }
}

/** 이 줄이 사라진 까닭이 고쳐서가 아니라 읽지 못하게 돼서인지. 그렇다면 '닫혔습니다'·'확인됨'이라고 하면 안 된다. */
fun CheckItem.lostReading(now: List<CheckItem>): Boolean {
    val item = when (this) {
        is CheckItem.WindowOpen -> StateItem.WINDOWS
        is CheckItem.DoorOpen -> StateItem.DOORS
        is CheckItem.ClosureOpen -> item
        is CheckItem.LightOn -> item
        else -> return false
    }
    return CheckItem.Unreadable(item) in now
}

/** 반납 체크가 차를 한 번 읽은 결과. [items]는 화면의 줄이고, [energy]는 잔량 게이지다. 이 차에서 잔량을 읽지 못했으면 null이다. */
data class CheckReading(val items: List<CheckItem>, val energy: EnergyGauge?)

object ReturnCheck {
    // 고칠 수 있는 항목. 못 읽으면 CheckItem.Unreadable이 된다.
    private val FIXABLE = listOf(
        StateItem.WINDOWS, StateItem.DOORS, StateItem.TRUNK, StateItem.CHARGE_PORT, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

    // 고칠 수 있는 항목의 직전 값을 몇 번까지 버틸지. 2초 주기로 약 6초 뒤에는 읽지 못함으로 드러낸다.
    private const val MAX_HELD_FAILURES = 2

    /**
     * 지금 상태를 인수 기록과 비교해 반납 전에 알릴 것을 고른다. 순서: 고칠 수 있는 것 → 읽지 못한 것 → 좌석 → 잔량 → 경고등.
     * 값이 없는 이유를 [VehicleState.unavailable]이 '읽지 못함'이라고 말한 항목만 [CheckItem.Unreadable]로 본다.
     * 지원하지 않는 항목은 고객이 확인할 일이 아니다.
     */
    fun items(pickup: VehicleState, now: VehicleState, policy: RentalPolicy = RentalPolicy()): List<CheckItem> = buildList {
        now.windowsOpen.opened().forEach { add(CheckItem.WindowOpen(it)) }
        now.doorsOpen.opened().forEach { add(CheckItem.DoorOpen(it)) }
        if (now.trunkOpen == true) add(CheckItem.ClosureOpen(StateItem.TRUNK))
        if (now.chargePortOpen == true) add(CheckItem.ClosureOpen(StateItem.CHARGE_PORT))
        listOf(
            StateItem.HEADLIGHTS to now.headlightsOn,
            StateItem.TAIL_LAMP to now.tailLampOn,
            StateItem.CABIN_LIGHT to now.cabinLightOn,
        ).filter { it.second == true }.forEach { add(CheckItem.LightOn(it.first)) }
        FIXABLE.filter { now.unavailable[it] is Unavailable.Failed }.forEach { add(CheckItem.Unreadable(it)) }
        // 운전석에는 반납하는 사람이 앉아 있으니 뺀다.
        now.seatsOccupied.filterKeys { it != Area.FRONT_LEFT }.opened().forEach { add(CheckItem.SeatDetected(it)) }
        energyDrop(StateItem.FUEL, pickup.fuelPercent, now.fuelPercent, policy)?.let(::add)
        energyDrop(StateItem.EV_SOC, pickup.evSocPercent, now.evSocPercent, policy)?.let(::add)
        val lit = now.telltales ?: return@buildList
        val before = pickup.telltales
        if (before == null) {
            if (lit.isNotEmpty()) add(CheckItem.TelltalesUnverified(lit))
        } else {
            (lit - before).takeIf { it.isNotEmpty() }?.let { add(CheckItem.NewTelltales(it)) }
        }
    }

    /**
     * 잔량 게이지의 값. 연료와 배터리가 다 있으면(하이브리드) 인수 때보다 모자란 쪽을 먼저 보여 준다. 배터리 용량은 인수 기록의 차 값을 먼저 쓰고,
     * 차가 주지 않았으면 업체 기준을 쓴다(리포트의 참고 정산과 같다).
     */
    fun energy(pickup: VehicleState, now: VehicleState, policy: RentalPolicy = RentalPolicy()): EnergyGauge? {
        fun gauge(item: StateItem, before: Double?, after: Double?, capacity: Double?, price: Int?) =
            if (before == null || after == null) null else EnergyGauge(item, before, after, policy.energyTolerancePercent, capacity, price, policy.basis)
        val gauges = listOfNotNull(
            gauge(StateItem.FUEL, pickup.fuelPercent, now.fuelPercent, policy.tankCapacityL, policy.fuelPricePerL),
            gauge(StateItem.EV_SOC, pickup.evSocPercent, now.evSocPercent, pickup.evCapacityKwh ?: now.evCapacityKwh ?: policy.batteryCapacityKwh, policy.electricityPricePerKwh),
        )
        return gauges.firstOrNull { !it.reached } ?: gauges.firstOrNull()
    }

    /** [readings]의 목록만 필요할 때 쓴다. */
    fun watch(
        vehicle: VehicleSource,
        pickup: VehicleState,
        policy: RentalPolicy = RentalPolicy(),
        pollEvery: Duration? = 2.seconds,
    ): Flow<List<CheckItem>> = readings(vehicle, pickup, policy, pollEvery).map { it.items }.distinctUntilChanged()

    /**
     * 반납 체크 화면이 떠 있는 동안 목록과 잔량을 다시 계산해 내보낸다. 고객이 창문을 닫으면 곧바로 목록에서 빠지고, 충전하면 게이지가 따라 올라간다.
     * 리스너가 없는 값(라이트 스위치 등)은 [pollEvery]마다 다시 읽는다. 구독이 끊겨도 주기 확인으로 이어 간다.
     * 일시 실패 때는 직전 값을 유지해([HeldReadings]) 열린 창문이 '닫혔습니다'로 바뀌지 않게 한다.
     */
    fun readings(
        vehicle: VehicleSource,
        pickup: VehicleState,
        policy: RentalPolicy = RentalPolicy(),
        pollEvery: Duration? = 2.seconds,
    ): Flow<CheckReading> = flow {
        val held = HeldReadings()
        val changes = vehicle.changes().map { }.catch { }
        val ticks = pollEvery?.let { every -> flow { while (true) { delay(every); emit(Unit) } } } ?: emptyFlow()
        // 구독 직후 리스너마다 현재 값을 주므로 신호가 한꺼번에 온다. 읽는 동안 온 신호는 하나로 합친다.
        merge(flowOf(Unit), changes, ticks).conflate().collect {
            val read = try {
                vehicle.state()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val state = held.next(read)
            emit(CheckReading(items(pickup, state, policy), energy(pickup, state, policy)))
        }
    }.distinctUntilChanged()

    /**
     * 읽기 결과에서 일시 실패를 걸러 낸다. 못 읽은 항목은 직전 값을 그대로 쓴다. 고칠 수 있는 항목은 그 사이 고객이 고쳤을 수 있어
     * [MAX_HELD_FAILURES]번까지만 버티고 읽지 못함으로 드러낸다. 좌석·잔량·경고등은 알려 주기만 하는 값이라 다시 읽을 때까지 유지한다.
     */
    private class HeldReadings {
        private var last = VehicleState()
        private val failures = mutableMapOf<StateItem, Int>()

        // read가 null이면 읽기 자체가 실패한 것이다. 지원하지 않는다고 알던 항목은 실패로 바꾸지 않는다.
        fun next(read: VehicleState?): VehicleState {
            val state = read ?: VehicleState(
                unavailable = StateItem.entries.associateWith { last.unavailable[it] as? Unavailable.Unsupported ?: Unavailable.Failed(VehicleState.NOT_READ) },
            )
            val failed = state.unavailable.filterValues { it is Unavailable.Failed }.keys
            failures.keys.retainAll(failed)
            failed.forEach { failures[it] = (failures[it] ?: 0) + 1 }
            val keep = failed.filter { last.has(it) && (it !in FIXABLE || failures.getValue(it) <= MAX_HELD_FAILURES) }
            last = state.overlay(last, keep.toSet())
            return last
        }
    }

    private fun energyDrop(item: StateItem, before: Double?, after: Double?, policy: RentalPolicy): CheckItem? {
        if (before == null || after == null || before - after <= policy.energyTolerancePercent) return null
        return CheckItem.EnergyDrop(item, before.roundToInt(), after.roundToInt())
    }

    private fun Map<Area, Boolean>.opened(): List<Area> = filterValues { it }.keys.sorted()
}
