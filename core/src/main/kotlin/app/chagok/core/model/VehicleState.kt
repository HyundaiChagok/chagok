package app.chagok.core.model

import java.time.Instant

/** 문·창문·좌석·타이어 위치. 국내 좌핸들 기준이라 FRONT_LEFT가 운전석이다. */
enum class Area(val label: String, val side: String) {
    FRONT_LEFT("운전석", "앞 왼쪽"),
    FRONT_RIGHT("조수석", "앞 오른쪽"),
    REAR_LEFT("뒷좌석 왼쪽", "뒤 왼쪽"),
    REAR_CENTER("뒷좌석 가운데", "뒤 가운데"),
    REAR_RIGHT("뒷좌석 오른쪽", "뒤 오른쪽"),
}

enum class FluidLevel(val label: String) {
    NORMAL("정상"),
    LOW("부족"),
}

/**
 * Pleos SDK 2.3.2는 바퀴별 공기압(getTirePressure)과 저압 기준값(getLowTirePressure)을 준다.
 * [lowPressure]는 어댑터가 공기압이 기준값 이하인지로 계산해 넣는다.
 */
data class TireState(
    val pressureKpa: Double? = null,
    val lowPressure: Boolean? = null,
)

/** 경고등. code는 SDK 상수 이름, label은 화면에 보일 이름. */
data class Telltale(val code: String, val label: String)

data class GeoPoint(val latitude: Double, val longitude: Double)

/** [VehicleState]의 항목. 리포트의 한 줄이자 값이 없는 이유를 붙이는 단위이고, key는 기록(JSON)에 쓰는 이름이다. */
enum class StateItem(val label: String, val key: String) {
    FUEL("연료", "fuelPercent"),
    EV_SOC("배터리", "evSocPercent"),
    EV_CHARGING("충전", "evCharging"),
    ODOMETER("주행거리", "odometerKm"),
    TRIP_SINCE_REFILL("주유 후 주행거리", "tripSinceRefillKm"),
    DOORS("문", "doorsOpen"),
    DOOR_LOCKS("문 잠금", "doorsLocked"),
    WINDOWS("창문", "windowsOpen"),
    HEADLIGHTS("전조등", "headlightsOn"),
    TAIL_LAMP("미등", "tailLampOn"),
    CABIN_LIGHT("실내등", "cabinLightOn"),
    PARKING_BRAKE("주차 브레이크", "parkingBrakeEngaged"),
    SEATS("좌석", "seatsOccupied"),
    ENGINE_OIL("엔진오일", "engineOil"),
    BRAKE_FLUID("브레이크액", "brakeFluid"),
    WASHER_FLUID("워셔액", "washerFluid"),
    TELLTALES("경고등", "telltales"),
    TIRES("타이어", "tires"),
    AIRBAGS("에어백", "airbagsDeployed"),
}

/** 값이 없는 이유. "없음"과 "모름"을 섞지 않으려고 나눈다. */
sealed interface Unavailable {
    /** 이 차가 그 값을 주지 않는다(check…Capabilities가 false). */
    data object Unsupported : Unavailable

    /** 지원하지만 이번에 읽지 못했다. reason은 TIMEOUT, PERMISSION 같은 짧은 코드다. */
    data class Failed(val reason: String) : Unavailable
}

/**
 * 한 시점의 차 상태.
 *
 * null이나 빈 맵은 "이 차가 지원하지 않거나 읽지 못함"이지 "없음"이 아니다. 어느 쪽인지는 [unavailable]에 적는다.
 * 단위는 거리 km, 잔량 0~100%, 타이어 공기압 kPa이다. SDK 단위가 다르면 어댑터에서 바꿔 넣는다.
 */
data class VehicleState(
    val fuelPercent: Double? = null,
    val evSocPercent: Double? = null,
    val evCharging: Boolean? = null,
    val odometerKm: Double? = null,
    val tripSinceRefillKm: Double? = null,
    val doorsOpen: Map<Area, Boolean> = emptyMap(),
    val doorsLocked: Map<Area, Boolean> = emptyMap(),
    val windowsOpen: Map<Area, Boolean> = emptyMap(),
    /** 전조등 스위치가 켜짐으로 고정된 상태. 자동 모드에서 켜진 것은 꺼 달라고 할 일이 아니므로 false로 넣는다. */
    val headlightsOn: Boolean? = null,
    val tailLampOn: Boolean? = null,
    val cabinLightOn: Boolean? = null,
    /** Pleos SDK의 Brake 도메인은 페달 상태만 준다. 주차 브레이크는 경고등 PARKING_BRAKE로만 알 수 있어 실차 어댑터는 채우지 않는다. */
    val parkingBrakeEngaged: Boolean? = null,
    val seatsOccupied: Map<Area, Boolean> = emptyMap(),
    val engineOil: FluidLevel? = null,
    val brakeFluid: FluidLevel? = null,
    val washerFluid: FluidLevel? = null,
    val telltales: Set<Telltale>? = null,
    val tires: Map<Area, TireState> = emptyMap(),
    val airbagsDeployed: Map<Area, Boolean> = emptyMap(),
    val unavailable: Map<StateItem, Unavailable> = emptyMap(),
) {
    /** 값이 있는지. 위치별 항목은 한 자리라도 값이 있으면 있다고 본다. */
    fun has(item: StateItem): Boolean = when (item) {
        StateItem.FUEL -> fuelPercent != null
        StateItem.EV_SOC -> evSocPercent != null
        StateItem.EV_CHARGING -> evCharging != null
        StateItem.ODOMETER -> odometerKm != null
        StateItem.TRIP_SINCE_REFILL -> tripSinceRefillKm != null
        StateItem.DOORS -> doorsOpen.isNotEmpty()
        StateItem.DOOR_LOCKS -> doorsLocked.isNotEmpty()
        StateItem.WINDOWS -> windowsOpen.isNotEmpty()
        StateItem.HEADLIGHTS -> headlightsOn != null
        StateItem.TAIL_LAMP -> tailLampOn != null
        StateItem.CABIN_LIGHT -> cabinLightOn != null
        StateItem.PARKING_BRAKE -> parkingBrakeEngaged != null
        StateItem.SEATS -> seatsOccupied.isNotEmpty()
        StateItem.ENGINE_OIL -> engineOil != null
        StateItem.BRAKE_FLUID -> brakeFluid != null
        StateItem.WASHER_FLUID -> washerFluid != null
        StateItem.TELLTALES -> telltales != null
        StateItem.TIRES -> tires.isNotEmpty()
        StateItem.AIRBAGS -> airbagsDeployed.isNotEmpty()
    }

    /** 값이 없는 이유. 값이 있으면 null이고, 이유를 적지 않았으면 읽지 못한 것으로 본다. */
    fun missing(item: StateItem): Unavailable? =
        if (has(item)) null else unavailable[item] ?: Unavailable.Failed(NOT_READ)

    /** [items]만 남긴 상태 */
    fun only(items: Set<StateItem>): VehicleState {
        fun <T> keep(item: StateItem, value: T, empty: T): T = if (item in items) value else empty
        return VehicleState(
            fuelPercent = keep(StateItem.FUEL, fuelPercent, null),
            evSocPercent = keep(StateItem.EV_SOC, evSocPercent, null),
            evCharging = keep(StateItem.EV_CHARGING, evCharging, null),
            odometerKm = keep(StateItem.ODOMETER, odometerKm, null),
            tripSinceRefillKm = keep(StateItem.TRIP_SINCE_REFILL, tripSinceRefillKm, null),
            doorsOpen = keep(StateItem.DOORS, doorsOpen, emptyMap()),
            doorsLocked = keep(StateItem.DOOR_LOCKS, doorsLocked, emptyMap()),
            windowsOpen = keep(StateItem.WINDOWS, windowsOpen, emptyMap()),
            headlightsOn = keep(StateItem.HEADLIGHTS, headlightsOn, null),
            tailLampOn = keep(StateItem.TAIL_LAMP, tailLampOn, null),
            cabinLightOn = keep(StateItem.CABIN_LIGHT, cabinLightOn, null),
            parkingBrakeEngaged = keep(StateItem.PARKING_BRAKE, parkingBrakeEngaged, null),
            seatsOccupied = keep(StateItem.SEATS, seatsOccupied, emptyMap()),
            engineOil = keep(StateItem.ENGINE_OIL, engineOil, null),
            brakeFluid = keep(StateItem.BRAKE_FLUID, brakeFluid, null),
            washerFluid = keep(StateItem.WASHER_FLUID, washerFluid, null),
            telltales = keep(StateItem.TELLTALES, telltales, null),
            tires = keep(StateItem.TIRES, tires, emptyMap()),
            airbagsDeployed = keep(StateItem.AIRBAGS, airbagsDeployed, emptyMap()),
            unavailable = unavailable.filterKeys { it in items },
        )
    }

    /** [items]는 [other]의 값으로, 나머지는 이 상태의 값으로 채운다. 값이 없는 이유도 같은 쪽에서 가져온다. */
    fun overlay(other: VehicleState, items: Set<StateItem>): VehicleState {
        fun <T> pick(item: StateItem, mine: T, theirs: T): T = if (item in items) theirs else mine
        return VehicleState(
            fuelPercent = pick(StateItem.FUEL, fuelPercent, other.fuelPercent),
            evSocPercent = pick(StateItem.EV_SOC, evSocPercent, other.evSocPercent),
            evCharging = pick(StateItem.EV_CHARGING, evCharging, other.evCharging),
            odometerKm = pick(StateItem.ODOMETER, odometerKm, other.odometerKm),
            tripSinceRefillKm = pick(StateItem.TRIP_SINCE_REFILL, tripSinceRefillKm, other.tripSinceRefillKm),
            doorsOpen = pick(StateItem.DOORS, doorsOpen, other.doorsOpen),
            doorsLocked = pick(StateItem.DOOR_LOCKS, doorsLocked, other.doorsLocked),
            windowsOpen = pick(StateItem.WINDOWS, windowsOpen, other.windowsOpen),
            headlightsOn = pick(StateItem.HEADLIGHTS, headlightsOn, other.headlightsOn),
            tailLampOn = pick(StateItem.TAIL_LAMP, tailLampOn, other.tailLampOn),
            cabinLightOn = pick(StateItem.CABIN_LIGHT, cabinLightOn, other.cabinLightOn),
            parkingBrakeEngaged = pick(StateItem.PARKING_BRAKE, parkingBrakeEngaged, other.parkingBrakeEngaged),
            seatsOccupied = pick(StateItem.SEATS, seatsOccupied, other.seatsOccupied),
            engineOil = pick(StateItem.ENGINE_OIL, engineOil, other.engineOil),
            brakeFluid = pick(StateItem.BRAKE_FLUID, brakeFluid, other.brakeFluid),
            washerFluid = pick(StateItem.WASHER_FLUID, washerFluid, other.washerFluid),
            telltales = pick(StateItem.TELLTALES, telltales, other.telltales),
            tires = pick(StateItem.TIRES, tires, other.tires),
            airbagsDeployed = pick(StateItem.AIRBAGS, airbagsDeployed, other.airbagsDeployed),
            unavailable = unavailable.filterKeys { it !in items } + other.unavailable.filterKeys { it in items },
        )
    }

    /** 저장할 기록용 상태. [items]만 남기고 값이 없는 항목마다 이유를 채워, 이유 없는 빈칸이 남지 않게 한다. */
    fun forRecord(items: Set<StateItem>): VehicleState {
        val kept = only(items)
        return kept.copy(unavailable = items.filterNot(kept::has).associateWith { kept.missing(it)!! })
    }

    companion object {
        /** 이유를 적지 않은 빈 값에 붙이는 사유 코드 */
        const val NOT_READ = "NOT_READ"
    }
}

data class VehicleSnapshot(
    val capturedAt: Instant,
    val location: GeoPoint?,
    val state: VehicleState,
)
