package app.chagok.core.model

import java.time.Instant

/** 세션 동안 기록할 변화. 연료·주행거리처럼 계속 바뀌는 값은 기록하지 않고 스냅샷끼리 비교한다. */
sealed interface VehicleEvent {
    data class DoorChanged(val area: Area, val open: Boolean) : VehicleEvent
    data class LockChanged(val area: Area, val locked: Boolean) : VehicleEvent
    data class WindowChanged(val area: Area, val open: Boolean) : VehicleEvent

    /** item은 HEADLIGHTS, TAIL_LAMP, CABIN_LIGHT 중 하나 */
    data class LightChanged(val item: StateItem, val on: Boolean) : VehicleEvent
    data class ParkingBrakeChanged(val engaged: Boolean) : VehicleEvent
    data class SeatOccupancyChanged(val area: Area, val occupied: Boolean) : VehicleEvent

    /** item은 ENGINE_OIL, BRAKE_FLUID, WASHER_FLUID 중 하나 */
    data class FluidChanged(val item: StateItem, val level: FluidLevel) : VehicleEvent
    data class TelltalesChanged(val active: Set<Telltale>) : VehicleEvent
    data class AirbagChanged(val area: Area, val deployed: Boolean) : VehicleEvent
    data class Moved(val point: GeoPoint) : VehicleEvent

    /** 앱이 직접 남기는 기록. 예: 시승 중 type="feature", detail="통풍 시트" */
    data class Note(val type: String, val detail: String = "") : VehicleEvent

    companion object {
        /** 차나 위치 데이터 구독이 끊겼을 때 남기는 [Note]의 type */
        const val SOURCE_ERROR = "source_error"
    }
}

/** 이 이벤트가 바꾼 항목. 위치 이동과 앱 기록은 항목이 없어 null */
val VehicleEvent.item: StateItem?
    get() = when (this) {
        is VehicleEvent.DoorChanged -> StateItem.DOORS
        is VehicleEvent.LockChanged -> StateItem.DOOR_LOCKS
        is VehicleEvent.WindowChanged -> StateItem.WINDOWS
        is VehicleEvent.LightChanged -> item
        is VehicleEvent.ParkingBrakeChanged -> StateItem.PARKING_BRAKE
        is VehicleEvent.SeatOccupancyChanged -> StateItem.SEATS
        is VehicleEvent.FluidChanged -> item
        is VehicleEvent.TelltalesChanged -> StateItem.TELLTALES
        is VehicleEvent.AirbagChanged -> StateItem.AIRBAGS
        is VehicleEvent.Moved, is VehicleEvent.Note -> null
    }

data class LoggedEvent(
    val at: Instant,
    val location: GeoPoint?,
    val event: VehicleEvent,
)
