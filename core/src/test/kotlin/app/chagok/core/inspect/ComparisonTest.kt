package app.chagok.core.inspect

import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonTest {
    private val t0 = Instant.parse("2026-11-03T00:12:00Z")

    private fun compare(before: VehicleState, after: VehicleState, vararg items: StateItem): Comparison =
        Inspector(StandardRules.all()).inspect(
            InspectionContext(VehicleSnapshot(t0, null, before), VehicleSnapshot(t0.plusSeconds(3_600), null, after)),
        ).compare(items.toList())

    @Test
    fun `규칙이 말한 항목은 분류하고, 말하지 않은 항목은 변화 없음으로 남긴다`() {
        val comparison = compare(
            VehicleState(fuelPercent = 82.0, engineOil = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW),
            VehicleState(fuelPercent = 61.0, engineOil = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW),
            StateItem.ENGINE_OIL, StateItem.FUEL, StateItem.WASHER_FLUID,
        )
        assertEquals(
            listOf(
                ComparisonRow(StateItem.FUEL, "82%", "61%", Verdict.NEEDS_CHECK, listOf("연료 82% → 61% (21%p 감소)")),
                ComparisonRow(StateItem.WASHER_FLUID, "부족", "부족", Verdict.NOTE, listOf("워셔액 부족 (시작 때부터)")),
                ComparisonRow(StateItem.ENGINE_OIL, "정상", "정상", Verdict.UNCHANGED, emptyList()),
            ),
            comparison.rows,
        )
    }

    @Test
    fun `값이 없으면 이유와 함께 확인 불가로 둔다`() {
        val comparison = compare(
            VehicleState(fuelPercent = 82.0, unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported)),
            VehicleState(
                unavailable = mapOf(
                    StateItem.FUEL to Unavailable.Failed("TIMEOUT"),
                    StateItem.CABIN_LIGHT to Unavailable.Unsupported,
                ),
            ),
            StateItem.FUEL, StateItem.CABIN_LIGHT, StateItem.ENGINE_OIL,
        )
        assertEquals(
            listOf(
                ComparisonRow(StateItem.FUEL, "82%", null, Verdict.UNKNOWN, listOf("읽지 못함 (시간 초과)")),
                ComparisonRow(StateItem.CABIN_LIGHT, null, null, Verdict.UNKNOWN, listOf("이 차에서 지원 안 함")),
                ComparisonRow(StateItem.ENGINE_OIL, null, null, Verdict.UNKNOWN, listOf("읽지 못함")),
            ),
            comparison.rows,
        )
    }

    @Test
    fun `반납 때만 보는 항목은 인수 때 값이 없어도 판단한다`() {
        val comparison = compare(
            VehicleState(),
            VehicleState(windowsOpen = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to false)),
            StateItem.WINDOWS,
        )
        assertEquals(listOf(ComparisonRow(StateItem.WINDOWS, null, "모두 닫힘", Verdict.UNCHANGED, emptyList())), comparison.rows)
    }

    @Test
    fun `휘발유차의 배터리처럼 애초에 없는 항목은 줄에서 뺀다`() {
        val noEv = mapOf(StateItem.EV_SOC to Unavailable.Unsupported, StateItem.EV_CHARGING to Unavailable.Unsupported)
        val comparison = compare(
            VehicleState(fuelPercent = 82.0, unavailable = noEv),
            VehicleState(fuelPercent = 81.0, unavailable = noEv),
            StateItem.FUEL, StateItem.EV_SOC, StateItem.EV_CHARGING,
        )
        assertEquals(listOf(StateItem.FUEL), comparison.rows.map { it.item })
        assertEquals(listOf(StateItem.EV_SOC, StateItem.EV_CHARGING), comparison.notApplicable)
    }

    @Test
    fun `리포트 값은 사람이 읽는 말로 적는다`() {
        val state = VehicleState(
            odometerKm = 23_722.4,
            doorsOpen = mapOf(Area.FRONT_LEFT to true, Area.REAR_RIGHT to true, Area.FRONT_RIGHT to false),
            tires = mapOf(Area.FRONT_LEFT to app.chagok.core.model.TireState(lowPressure = true)),
            telltales = emptySet(),
        )
        assertEquals("23,722km", state.display(StateItem.ODOMETER))
        assertEquals("운전석, 뒷좌석 오른쪽 열림", state.display(StateItem.DOORS))
        assertEquals("앞 왼쪽 저압", state.display(StateItem.TIRES))
        assertEquals("없음", state.display(StateItem.TELLTALES))
        assertEquals(null, state.display(StateItem.WINDOWS))
    }
}
