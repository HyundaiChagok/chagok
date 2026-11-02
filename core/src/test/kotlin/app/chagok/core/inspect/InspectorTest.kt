package app.chagok.core.inspect

import app.chagok.core.inspect.Severity.INFO
import app.chagok.core.inspect.Severity.WARNING
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class InspectorTest {
    private val t0 = Instant.parse("2026-11-01T05:00:00Z") // 14:00 KST

    private fun inspect(
        before: VehicleState,
        after: VehicleState,
        vararg rules: InspectionRule,
        events: List<LoggedEvent>? = null,
    ): InspectionReport = Inspector(if (rules.isEmpty()) StandardRules.all() else rules.toList()).inspect(
        InspectionContext(
            VehicleSnapshot(t0, null, before),
            VehicleSnapshot(t0.plusSeconds(72 * 60), null, after),
            events,
        ),
    )

    @Test
    fun `모르는 값은 판단하지 않는다`() {
        assertEquals(emptyList(), inspect(VehicleState(), VehicleState()).findings)
    }

    @Test
    fun `연료가 허용치보다 많이 줄면 경고`() {
        val report = inspect(VehicleState(fuelPercent = 82.0), VehicleState(fuelPercent = 41.0), StandardRules.Energy())
        assertEquals(listOf(Finding("energy", WARNING, "연료 82% → 41%", StateItem.FUEL)), report.findings)
    }

    @Test
    fun `허용치 안의 잔량 변화는 무시하고 충전은 참고로 둔다`() {
        val report = inspect(
            VehicleState(fuelPercent = 82.0, evSocPercent = 30.0),
            VehicleState(fuelPercent = 80.5, evSocPercent = 80.0),
            StandardRules.Energy(),
        )
        assertEquals(listOf(Finding("energy", INFO, "배터리 30% → 80%", StateItem.EV_SOC)), report.findings)
    }

    @Test
    fun `주유 후 주행거리가 초기화되면 주유로 본다`() {
        val before = VehicleState(odometerKm = 10_000.0, tripSinceRefillKm = 120.0)
        val refueled = inspect(before, VehicleState(odometerKm = 10_130.0, tripSinceRefillKm = 15.0), StandardRules.Refuel())
        val notRefueled = inspect(before, VehicleState(odometerKm = 10_130.0, tripSinceRefillKm = 250.0), StandardRules.Refuel())

        assertEquals(
            listOf(Finding("refuel", INFO, "사용 중 주유 기록 있음 (주유 후 15km 주행)", StateItem.FUEL)),
            refueled.findings,
        )
        assertEquals(emptyList(), notRefueled.findings)
    }

    @Test
    fun `소모품 상태는 이전과 이후를 함께 적는다`() {
        val report = inspect(
            VehicleState(engineOil = FluidLevel.LOW, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW),
            VehicleState(engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.LOW, washerFluid = FluidLevel.LOW),
            StandardRules.Fluids(),
        )
        assertEquals(
            listOf(
                Finding("fluids", INFO, "엔진오일 부족 → 정상", StateItem.ENGINE_OIL),
                Finding("fluids", WARNING, "브레이크액 정상 → 부족", StateItem.BRAKE_FLUID),
                Finding("fluids", INFO, "워셔액 부족 (시작 때부터)", StateItem.WASHER_FLUID),
            ),
            report.findings,
        )
    }

    @Test
    fun `새로 켜진 경고등만 경고로 올린다`() {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val engine = Telltale("ENGINE", "엔진 점검")
        val abs = Telltale("ABS", "ABS")
        val report = inspect(
            VehicleState(telltales = setOf(engine, abs)),
            VehicleState(telltales = setOf(engine, tpms)),
            StandardRules.Telltales(),
        )
        assertEquals(
            listOf(
                Finding("telltales", WARNING, "경고등 켜짐: 타이어 공기압", StateItem.TELLTALES),
                Finding("telltales", INFO, "경고등 켜짐 (시작 때부터): 엔진 점검", StateItem.TELLTALES),
                Finding("telltales", INFO, "경고등 꺼짐: ABS", StateItem.TELLTALES),
            ),
            report.findings,
        )
    }

    @Test
    fun `렌터카 말투로 바꾸면 인수 때부터, 대여 중으로 적는다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val report = inspect(
            VehicleState(odometerKm = 100.0, tripSinceRefillKm = 300.0, washerFluid = FluidLevel.LOW, telltales = setOf(engine)),
            VehicleState(odometerKm = 150.0, tripSinceRefillKm = 20.0, washerFluid = FluidLevel.LOW, telltales = setOf(engine)),
            StandardRules.Refuel(terms = Terms.RENTAL), StandardRules.Fluids(Terms.RENTAL), StandardRules.Telltales(Terms.RENTAL),
        )
        assertEquals(
            listOf(
                "대여 중 주유 기록 있음 (주유 후 20km 주행)",
                "워셔액 부족 (인수 때부터)",
                "경고등 켜짐 (인수 때부터): 엔진 점검",
            ),
            report.findings.map { it.message },
        )
    }

    @Test
    fun `타이어는 저압 경고나 공기압 급감을 본다`() {
        val report = inspect(
            VehicleState(tires = mapOf(Area.FRONT_LEFT to TireState(230.0), Area.REAR_RIGHT to TireState(lowPressure = false))),
            VehicleState(tires = mapOf(Area.FRONT_LEFT to TireState(205.0), Area.REAR_RIGHT to TireState(lowPressure = true))),
            StandardRules.Tires(),
        )
        assertEquals(
            listOf(
                Finding("tires", WARNING, "앞 왼쪽 타이어 공기압 230 → 205kPa", StateItem.TIRES),
                Finding("tires", WARNING, "뒤 오른쪽 타이어 공기압 낮음", StateItem.TIRES),
            ),
            report.findings,
        )
    }

    @Test
    fun `에어백은 새로 전개된 것만 경고하고 원인은 적지 않는다`() {
        val report = inspect(
            VehicleState(airbagsDeployed = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to true)),
            VehicleState(airbagsDeployed = mapOf(Area.FRONT_LEFT to true, Area.FRONT_RIGHT to true)),
            StandardRules.Airbags(),
        )
        assertEquals(
            listOf(
                Finding("airbags", WARNING, "에어백 전개 기록: 운전석", StateItem.AIRBAGS),
                Finding("airbags", INFO, "에어백 전개 기록 (시작 때부터): 조수석", StateItem.AIRBAGS),
            ),
            report.findings,
        )
    }

    @Test
    fun `종료 시점에 열린 창문과 켜진 라이트, 남은 물건`() {
        val after = VehicleState(
            windowsOpen = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to true),
            headlightsOn = true,
            tailLampOn = false,
            cabinLightOn = true,
            seatsOccupied = mapOf(Area.FRONT_LEFT to true, Area.REAR_LEFT to true),
        )
        val report = inspect(VehicleState(), after, StandardRules.LeftOpen(), StandardRules.LeftBehind())
        assertEquals(
            listOf(
                Finding("left_open", WARNING, "창문 열림: 조수석", StateItem.WINDOWS),
                Finding("left_open", WARNING, "전조등 켜짐", StateItem.HEADLIGHTS),
                Finding("left_open", WARNING, "실내등 켜짐", StateItem.CABIN_LIGHT),
                Finding("left_behind", WARNING, "좌석 감지: 뒷좌석 왼쪽 (두고 내린 물건 확인)", StateItem.SEATS),
            ),
            report.findings,
        )
    }

    @Test
    fun `기록이 있으면 문 열림 횟수를 세고, 없으면 판단하지 않는다`() {
        fun door(area: Area, open: Boolean) = LoggedEvent(t0, null, VehicleEvent.DoorChanged(area, open))
        val events = listOf(
            door(Area.FRONT_LEFT, true), door(Area.FRONT_LEFT, false),
            door(Area.FRONT_RIGHT, true), door(Area.FRONT_LEFT, true),
        )
        val rule = StandardRules.DoorActivity()

        assertEquals(
            listOf(Finding("door_activity", INFO, "문 열림 3회 (운전석 2, 조수석 1)", StateItem.DOORS)),
            inspect(VehicleState(), VehicleState(), rule, events = events).findings,
        )
        assertEquals(
            listOf(Finding("door_activity", INFO, "문 열림 없음", StateItem.DOORS)),
            inspect(VehicleState(), VehicleState(), rule, events = emptyList()).findings,
        )
        assertEquals(emptyList(), inspect(VehicleState(), VehicleState(), rule, events = null).findings)
    }

    @Test
    fun `주행거리가 기준을 넘으면 경고`() {
        val rule = StandardRules.Distance(warnAboveKm = 3.0)
        val report = inspect(VehicleState(odometerKm = 100.0), VehicleState(odometerKm = 104.2), rule)
        assertEquals(listOf(Finding("distance", WARNING, "주행 4.2km", StateItem.ODOMETER)), report.findings)
    }

    @Test
    fun `리포트 텍스트는 경고와 참고를 나눠 적는다`() {
        val report = inspect(
            VehicleState(odometerKm = 100.0, fuelPercent = 82.0, engineOil = FluidLevel.LOW),
            VehicleState(odometerKm = 104.2, fuelPercent = 41.0, engineOil = FluidLevel.NORMAL),
        )
        assertEquals(
            """
            점검 리포트
            2026-11-01 14:00 → 2026-11-01 15:12 (1시간 12분)

            확인 필요
            - 연료 82% → 41%

            참고
            - 주행 4.2km
            - 엔진오일 부족 → 정상
            """.trimIndent(),
            report.toText(),
        )
    }

    @Test
    fun `특이사항이 없으면 그렇게 적는다`() {
        val report = inspect(VehicleState(), VehicleState())
        assertEquals("점검 리포트\n2026-11-01 14:00 → 2026-11-01 15:12 (1시간 12분)\n\n특이사항 없음", report.toText())
    }
}
