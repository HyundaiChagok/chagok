package app.chagok.demo

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.driven
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withLowTire
import app.chagok.core.fake.withSeat
import app.chagok.core.fake.withWindow
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState

/**
 * 시연용 가짜 차량(기능 정의서 F-SYS-07). 휘발유 렌터카 한 대를 흉내 내고, 대여 중 변화(312km 주행, 연료 21%p 감소,
 * 타이어 저압)와 반납 때 놓치기 쉬운 상태(창문, 미등, 좌석)를 직원 화면의 버튼으로 재현한다.
 */
class DemoVehicle {
    val fake = FakeVehicle(START)

    fun driveDuringRental() = fake.update { driven(km = 312.0, fuelUsedPercent = 21.0).withLowTire(Area.FRONT_LEFT) }

    fun toggleWindow() = fake.update { withWindow(Area.FRONT_RIGHT, windowsOpen[Area.FRONT_RIGHT] != true) }

    fun toggleTailLamp() = fake.update { withLight(StateItem.TAIL_LAMP, tailLampOn != true) }

    fun toggleSeat() = fake.update { withSeat(Area.FRONT_RIGHT, seatsOccupied[Area.FRONT_RIGHT] != true) }

    fun reset() = fake.update { START }

    companion object {
        private val FOUR = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.REAR_LEFT, Area.REAR_RIGHT)

        val START = VehicleState(
            fuelPercent = 82.0, odometerKm = 23_410.0, tripSinceRefillKm = 210.0,
            doorsOpen = FOUR.associateWith { false }, windowsOpen = FOUR.associateWith { false },
            headlightsOn = false, tailLampOn = false,
            seatsOccupied = mapOf(Area.FRONT_LEFT to true),
            engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW,
            telltales = emptySet(),
            tires = FOUR.associateWith { TireState(lowPressure = false) },
            airbagsDeployed = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to false),
            unavailable = mapOf(
                StateItem.EV_SOC to Unavailable.Unsupported,
                StateItem.EV_CHARGING to Unavailable.Unsupported,
                StateItem.CABIN_LIGHT to Unavailable.Unsupported,
            ),
        )

        /**
         * 시연 모드에서 대본 값을 쓰는 항목. 에뮬레이터(전기차)가 바꿀 수 없거나 휘발유차 이야기에만 있는 값이다.
         * 문·창문·라이트·경고등(그리고 반납 체크의 좌석)은 Virtual Console로 바꿀 수 있어 SDK 값을 쓴다.
         */
        val SCRIPTED: Set<StateItem> = setOf(
            StateItem.FUEL, StateItem.TRIP_SINCE_REFILL, StateItem.ODOMETER, StateItem.EV_SOC, StateItem.EV_CHARGING,
            StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.TIRES, StateItem.AIRBAGS,
        )
    }
}
