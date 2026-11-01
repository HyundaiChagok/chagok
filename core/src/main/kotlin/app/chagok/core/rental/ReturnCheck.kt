package app.chagok.core.rental

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    /** 차 안에서 바로 고칠 수 있는 것(창문·문·라이트) */
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

    /** item은 HEADLIGHTS, TAIL_LAMP, CABIN_LIGHT 중 하나 */
    data class LightOn(val item: StateItem) : CheckItem {
        override val label: String get() = item.label
        override val detail: String get() = "켜짐"
        override val subject: String get() = item.label
        override val fixable: Boolean get() = true
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
}

object ReturnCheck {
    /** 지금 상태를 인수 기록과 비교해 반납 전에 알릴 것을 고른다. 순서: 고칠 수 있는 것 → 좌석 → 잔량 → 경고등 */
    fun items(pickup: VehicleState, now: VehicleState, policy: RentalPolicy = RentalPolicy()): List<CheckItem> = buildList {
        now.windowsOpen.opened().forEach { add(CheckItem.WindowOpen(it)) }
        now.doorsOpen.opened().forEach { add(CheckItem.DoorOpen(it)) }
        listOf(
            StateItem.HEADLIGHTS to now.headlightsOn,
            StateItem.TAIL_LAMP to now.tailLampOn,
            StateItem.CABIN_LIGHT to now.cabinLightOn,
        ).filter { it.second == true }.forEach { add(CheckItem.LightOn(it.first)) }
        // 운전석에는 반납하는 사람이 앉아 있으니 뺀다.
        now.seatsOccupied.filterKeys { it != Area.FRONT_LEFT }.opened().forEach { add(CheckItem.SeatDetected(it)) }
        energyDrop(StateItem.FUEL, pickup.fuelPercent, now.fuelPercent, policy)?.let(::add)
        energyDrop(StateItem.EV_SOC, pickup.evSocPercent, now.evSocPercent, policy)?.let(::add)
        val lit = now.telltales ?: return@buildList
        (lit - pickup.telltales.orEmpty()).takeIf { it.isNotEmpty() }?.let { add(CheckItem.NewTelltales(it)) }
    }

    /**
     * 반납 체크 화면이 떠 있는 동안 목록을 다시 계산해 내보낸다. 고객이 창문을 닫으면 곧바로 목록에서 빠진다.
     * 리스너가 없는 값(라이트 스위치 등)은 [pollEvery]마다 다시 읽는다. 구독이 끊겨도 주기 확인으로 이어 간다.
     */
    fun watch(
        vehicle: VehicleSource,
        pickup: VehicleState,
        policy: RentalPolicy = RentalPolicy(),
        pollEvery: Duration? = 2.seconds,
    ): Flow<List<CheckItem>> {
        val changes = vehicle.changes().map { }.catch { }
        val ticks = pollEvery?.let { every -> flow { while (true) { delay(every); emit(Unit) } } } ?: emptyFlow()
        return merge(flowOf(Unit), changes, ticks)
            .map { items(pickup, vehicle.state(), policy) }
            .distinctUntilChanged()
    }

    private fun energyDrop(item: StateItem, before: Double?, after: Double?, policy: RentalPolicy): CheckItem? {
        if (before == null || after == null || before - after <= policy.energyTolerancePercent) return null
        return CheckItem.EnergyDrop(item, before.roundToInt(), after.roundToInt())
    }

    private fun Map<Area, Boolean>.opened(): List<Area> = filterValues { it }.keys.sorted()
}
