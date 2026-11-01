package app.chagok.core.fake

import app.chagok.core.model.Area
import app.chagok.core.model.GeoPoint
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.source.LocationSource
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 실제 차 없이 쓰는 [VehicleSource]. 테스트와 시연용이며,
 * 에뮬레이터에서 차 값을 바꿀 수 없을 때 [Scenario]로 시연 흐름을 대신 재생한다.
 */
class FakeVehicle(initial: VehicleState = VehicleState()) : VehicleSource {
    @Volatile
    var current: VehicleState = initial
        private set

    private val events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)

    override suspend fun state(): VehicleState = current

    override fun changes(): Flow<VehicleEvent> = events.asSharedFlow()

    /** 상태를 바꾸고, 바뀐 문·잠금·창문·라이트·주차 브레이크·착석·소모품·경고등·에어백을 이벤트로 내보낸다. */
    @Synchronized
    fun update(transform: VehicleState.() -> VehicleState) {
        val old = current
        current = old.transform()
        eventsBetween(old, current).forEach { events.tryEmit(it) }
    }
}

class FakeLocation(start: GeoPoint? = null) : LocationSource {
    @Volatile
    private var point: GeoPoint? = start

    private val updates = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 64)

    override suspend fun current(): GeoPoint? = point

    override fun updates(): Flow<GeoPoint> = updates.asSharedFlow()

    fun moveTo(next: GeoPoint) {
        point = next
        updates.tryEmit(next)
    }
}

internal fun eventsBetween(old: VehicleState, new: VehicleState): List<VehicleEvent> = buildList {
    changed(old.doorsOpen, new.doorsOpen).forEach { (area, open) -> add(VehicleEvent.DoorChanged(area, open)) }
    changed(old.doorsLocked, new.doorsLocked).forEach { (area, locked) -> add(VehicleEvent.LockChanged(area, locked)) }
    changed(old.windowsOpen, new.windowsOpen).forEach { (area, open) -> add(VehicleEvent.WindowChanged(area, open)) }
    listOf(
        Triple(StateItem.HEADLIGHTS, old.headlightsOn, new.headlightsOn),
        Triple(StateItem.TAIL_LAMP, old.tailLampOn, new.tailLampOn),
        Triple(StateItem.CABIN_LIGHT, old.cabinLightOn, new.cabinLightOn),
    ).forEach { (item, was, now) -> now?.takeIf { it != was }?.let { add(VehicleEvent.LightChanged(item, it)) } }
    new.parkingBrakeEngaged?.takeIf { it != old.parkingBrakeEngaged }?.let { add(VehicleEvent.ParkingBrakeChanged(it)) }
    changed(old.seatsOccupied, new.seatsOccupied).forEach { (area, occupied) ->
        add(VehicleEvent.SeatOccupancyChanged(area, occupied))
    }
    listOf(
        Triple(StateItem.ENGINE_OIL, old.engineOil, new.engineOil),
        Triple(StateItem.BRAKE_FLUID, old.brakeFluid, new.brakeFluid),
        Triple(StateItem.WASHER_FLUID, old.washerFluid, new.washerFluid),
    ).forEach { (item, was, now) -> now?.takeIf { it != was }?.let { add(VehicleEvent.FluidChanged(item, it)) } }
    new.telltales?.takeIf { it != old.telltales }?.let { add(VehicleEvent.TelltalesChanged(it)) }
    changed(old.airbagsDeployed, new.airbagsDeployed).forEach { (area, deployed) ->
        add(VehicleEvent.AirbagChanged(area, deployed))
    }
}

private fun changed(old: Map<Area, Boolean>, new: Map<Area, Boolean>): List<Pair<Area, Boolean>> =
    new.filter { (area, value) -> old[area] != value }.toList().sortedBy { it.first }
