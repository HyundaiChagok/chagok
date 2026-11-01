package app.chagok.core.rental

import app.chagok.core.inspect.Finding
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.Inspector
import app.chagok.core.inspect.Severity
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RentalRulesTest {
    private val t0 = Instant.parse("2026-11-03T00:12:00Z")

    private fun findings(before: VehicleState, after: VehicleState, policy: RentalPolicy = RentalPolicy()): List<Finding> =
        Inspector(Rental.rules(policy)).inspect(
            InspectionContext(VehicleSnapshot(t0, null, before), VehicleSnapshot(t0.plusSeconds(86_400), null, after)),
        ).findings

    @Test
    fun `업체가 탱크 용량과 단가를 정하면 참고 정산액을 붙인다`() {
        val policy = RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700)
        val settlement = findings(VehicleState(fuelPercent = 82.0), VehicleState(fuelPercent = 61.0), policy)
            .filter { it.ruleId == "fuel_settlement" }
        assertEquals(
            listOf(Finding("fuel_settlement", Severity.INFO, "참고 정산(업체 기준): 약 9.9L · 16,800원 · 청구 확정은 업체", StateItem.FUEL)),
            settlement,
        )
    }

    @Test
    fun `정하지 않았거나 허용치 안이거나 늘었으면 정산액을 붙이지 않는다`() {
        val policy = RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700)
        fun settled(before: Double, after: Double, p: RentalPolicy = policy) =
            findings(VehicleState(fuelPercent = before), VehicleState(fuelPercent = after), p).any { it.ruleId == "fuel_settlement" }

        assertEquals(false, settled(82.0, 61.0, RentalPolicy()))
        assertEquals(false, settled(82.0, 80.5))
        assertEquals(false, settled(40.0, 90.0))
    }

    @Test
    fun `렌터카 규칙은 문 열림 횟수와 좌석을 리포트에 넣지 않는다`() {
        val result = findings(
            VehicleState(doorsOpen = mapOf(Area.FRONT_LEFT to false)),
            VehicleState(doorsOpen = mapOf(Area.FRONT_LEFT to false), seatsOccupied = mapOf(Area.REAR_LEFT to true)),
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `대여 중에는 차의 상태 변화만 남긴다`() {
        assertTrue(Rental.recordsDuringRental(VehicleEvent.TelltalesChanged(emptySet())))
        assertTrue(Rental.recordsDuringRental(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)))
        assertTrue(Rental.recordsDuringRental(VehicleEvent.AirbagChanged(Area.FRONT_LEFT, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.DoorChanged(Area.FRONT_LEFT, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.SeatOccupancyChanged(Area.FRONT_RIGHT, true)))
    }

    @Test
    fun `금지어는 고정 안내 문구에서만 허용한다`() {
        assertEquals(emptyList(), Wording.violations("참고 정산(업체 기준): 약 9.9L · 16,800원 · 청구 확정은 업체"))
        assertEquals(emptyList(), Wording.violations(RentalReport.DISCLAIMER))
        assertEquals(listOf("책임", "청구"), Wording.violations("고객 책임으로 청구합니다"))
    }

    @Test
    fun `렌터카 규칙이 내는 모든 문구에 금지어가 없다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val all = Area.entries.associateWith { true }
        val before = VehicleState(
            fuelPercent = 82.0, evSocPercent = 30.0, odometerKm = 100.0, tripSinceRefillKm = 300.0,
            engineOil = FluidLevel.LOW, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW,
            telltales = setOf(engine, Telltale("ABS", "ABS")), tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = false)),
            airbagsDeployed = mapOf(Area.FRONT_RIGHT to true, Area.FRONT_LEFT to false),
        )
        val after = VehicleState(
            fuelPercent = 41.0, evSocPercent = 80.0, odometerKm = 412.0, tripSinceRefillKm = 20.0,
            engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.LOW, washerFluid = FluidLevel.LOW,
            telltales = setOf(engine, Telltale("TPMS", "타이어 공기압")), tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = true)),
            airbagsDeployed = all, doorsOpen = all, windowsOpen = all, headlightsOn = true, tailLampOn = true, cabinLightOn = true,
        )
        val messages = findings(before, after, RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700)).map { it.message }

        assertTrue(messages.size >= 15, messages.toString())
        messages.forEach { assertEquals(emptyList(), Wording.violations(it), it) }
    }
}
