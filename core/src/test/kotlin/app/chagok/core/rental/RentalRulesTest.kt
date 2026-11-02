package app.chagok.core.rental

import app.chagok.core.inspect.ComparisonRow
import app.chagok.core.inspect.Finding
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.Inspector
import app.chagok.core.inspect.Severity
import app.chagok.core.inspect.Verdict
import app.chagok.core.inspect.compare
import app.chagok.core.inspect.display
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
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

    private val evPolicy = RentalPolicy(batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)

    private fun batteryNotes(before: VehicleState, after: VehicleState, policy: RentalPolicy = evPolicy) =
        findings(before, after, policy).filter { it.ruleId == "battery_settlement" }

    @Test
    fun `전기차는 줄어든 배터리를 kWh와 참고 금액으로 보여 주고 확정은 업체 몫이다`() {
        assertEquals(
            listOf(Finding("battery_settlement", Severity.INFO, "참고 정산(업체 기준): 약 16.3kWh · 5,700원 · 청구 확정은 업체", StateItem.EV_SOC)),
            batteryNotes(VehicleState(evSocPercent = 82.0), VehicleState(evSocPercent = 61.0)),
        )
    }

    @Test
    fun `차가 배터리 용량을 주면 업체 기준보다 먼저 쓴다`() {
        val notes = batteryNotes(VehicleState(evSocPercent = 80.0, evCapacityKwh = 64.0), VehicleState(evSocPercent = 60.0, evCapacityKwh = 64.0))
        assertEquals("참고 정산(업체 기준): 약 12.8kWh · 4,500원 · 청구 확정은 업체", notes.single().message)
    }

    @Test
    fun `인수 때 읽은 용량을 먼저 쓰고 없으면 반납 때 값을 쓴다`() {
        val fromPickup = batteryNotes(VehicleState(evSocPercent = 80.0, evCapacityKwh = 64.0), VehicleState(evSocPercent = 60.0, evCapacityKwh = 77.4))
        val fromReturn = batteryNotes(VehicleState(evSocPercent = 80.0), VehicleState(evSocPercent = 60.0, evCapacityKwh = 64.0))
        assertEquals(fromPickup.single().message, fromReturn.single().message)
    }

    @Test
    fun `전기 기준을 정하지 않았거나 허용치 안이거나 충전했으면 정산액을 붙이지 않는다`() {
        fun settled(before: Double, after: Double, p: RentalPolicy = evPolicy) =
            batteryNotes(VehicleState(evSocPercent = before), VehicleState(evSocPercent = after), p).isNotEmpty()

        assertEquals(false, settled(82.0, 61.0, RentalPolicy()), "기준 없음")
        assertEquals(false, settled(82.0, 61.0, RentalPolicy(batteryCapacityKwh = 77.4)), "단가 없음")
        assertEquals(false, settled(82.0, 61.0, RentalPolicy(electricityPricePerKwh = 350)), "차도 업체도 용량을 모름")
        assertEquals(false, settled(82.0, 80.5), "허용치 안")
        assertEquals(false, settled(40.0, 90.0), "충전함")
    }

    @Test
    fun `배터리 줄에 감소와 참고 정산이 함께 들어가 고객 웹이 금액을 찾는다`() {
        val report = Inspector(Rental.rules(evPolicy)).inspect(
            InspectionContext(
                VehicleSnapshot(t0, null, VehicleState(evSocPercent = 82.0)),
                VehicleSnapshot(t0.plusSeconds(60), null, VehicleState(evSocPercent = 61.0)),
            ),
        )
        val row = report.compare(listOf(StateItem.EV_SOC)).rows.single()
        assertEquals(Verdict.NEEDS_CHECK, row.verdict)
        assertEquals(
            listOf("배터리 82% → 61%", "참고 정산(업체 기준): 약 16.3kWh · 5,700원 · 청구 확정은 업체"),
            row.notes,
        )
    }

    private val tpmsLamp = Telltale(Telltale.TPMS, "타이어 공기압")
    private val fourFine = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.REAR_LEFT, Area.REAR_RIGHT)
        .associateWith { TireState(pressureKpa = 230.0, lowPressure = false) }

    private fun tireRow(before: VehicleState, after: VehicleState) = Inspector(Rental.rules()).inspect(
        InspectionContext(VehicleSnapshot(t0, null, before), VehicleSnapshot(t0.plusSeconds(60), null, after)),
    ).compare(listOf(StateItem.TIRES)).rows.single()

    @Test
    fun `인수 때부터 저압이던 타이어는 변화 없음이 아니라 인수 때부터라고 적는다`() {
        val low = mapOf(Area.FRONT_LEFT to TireState(pressureKpa = 120.0, lowPressure = true))
        val row = tireRow(VehicleState(tires = low), VehicleState(tires = low))

        assertEquals(Verdict.NOTE, row.verdict)
        assertEquals(listOf("앞 왼쪽 타이어 공기압 낮음 (인수 때부터)"), row.notes)
    }

    @Test
    fun `대여 중 저압이 되면 확인 필요이고 회복하면 참고다`() {
        val fine = mapOf(Area.FRONT_LEFT to TireState(pressureKpa = 230.0, lowPressure = false))
        val low = mapOf(Area.FRONT_LEFT to TireState(pressureKpa = 120.0, lowPressure = true))

        assertEquals(Verdict.NEEDS_CHECK, tireRow(VehicleState(tires = fine), VehicleState(tires = low)).verdict)
        val recovered = tireRow(VehicleState(tires = low), VehicleState(tires = fine))
        assertEquals(Verdict.NOTE, recovered.verdict)
        assertEquals(listOf("앞 왼쪽 타이어 공기압 회복"), recovered.notes)
    }

    @Test
    fun `경고등이 켜졌는데 수치가 모두 정상이면 낮은 쪽으로 보고 저압 없음이라고 하지 않는다`() {
        val after = VehicleState(tires = fourFine, telltales = setOf(tpmsLamp))
        assertEquals("공기압 경고등 켜짐", after.display(StateItem.TIRES))

        val row = tireRow(VehicleState(tires = fourFine, telltales = emptySet()), after)
        assertEquals(Verdict.NEEDS_CHECK, row.verdict)
        assertEquals(listOf("타이어 공기압 경고등 켜짐"), row.notes)
    }

    @Test
    fun `수치가 낮은데 경고등이 꺼져 있어도 낮은 쪽으로 본다`() {
        val after = VehicleState(tires = fourFine + (Area.REAR_LEFT to TireState(pressureKpa = 120.0, lowPressure = true)), telltales = emptySet())
        assertEquals("뒤 왼쪽 저압", after.display(StateItem.TIRES))
        assertEquals(Verdict.NEEDS_CHECK, tireRow(VehicleState(tires = fourFine, telltales = emptySet()), after).verdict)
    }

    @Test
    fun `수치와 경고등이 함께 저압을 말하면 타이어 줄은 바퀴만 적는다`() {
        val low = fourFine + (Area.FRONT_LEFT to TireState(pressureKpa = 120.0, lowPressure = true))
        val row = tireRow(VehicleState(tires = fourFine, telltales = emptySet()), VehicleState(tires = low, telltales = setOf(tpmsLamp)))
        assertEquals(listOf("앞 왼쪽 타이어 공기압 낮음"), row.notes)
    }

    @Test
    fun `공기압 수치를 주지 않는 차는 경고등으로 타이어 칸을 채운다`() {
        val noKpa = mapOf(StateItem.TIRES to Unavailable.Unsupported)
        val off = VehicleState(telltales = emptySet(), unavailable = noKpa)
        val on = VehicleState(telltales = setOf(tpmsLamp), unavailable = noKpa)
        assertEquals("공기압 경고 없음", off.display(StateItem.TIRES))
        assertEquals("공기압 경고등 켜짐", on.display(StateItem.TIRES))

        assertEquals(ComparisonRow(StateItem.TIRES, "공기압 경고 없음", "공기압 경고 없음", Verdict.UNCHANGED, emptyList()), tireRow(off, off))
        assertEquals(
            ComparisonRow(StateItem.TIRES, "공기압 경고 없음", "공기압 경고등 켜짐", Verdict.NEEDS_CHECK, listOf("타이어 공기압 경고등 켜짐")),
            tireRow(off, on),
        )
        assertEquals(listOf("타이어 공기압 경고등 켜짐 (인수 때부터)"), tireRow(on, on).notes)
        assertEquals(Verdict.NOTE, tireRow(on, on).verdict)
        assertEquals(listOf("타이어 공기압 경고등 꺼짐"), tireRow(on, off).notes)
        assertEquals(listOf("타이어 공기압 경고등 켜짐 (인수 때 확인 못 함)"), tireRow(VehicleState(unavailable = noKpa), on).notes)
    }

    @Test
    fun `수치도 경고등도 없는 차는 타이어 줄을 만들지 않는다`() {
        val nothing = VehicleState(
            unavailable = mapOf(StateItem.TIRES to Unavailable.Unsupported, StateItem.TELLTALES to Unavailable.Unsupported),
        )
        assertEquals(null, nothing.display(StateItem.TIRES))
        val comparison = Inspector(Rental.rules()).inspect(
            InspectionContext(VehicleSnapshot(t0, null, nothing), VehicleSnapshot(t0.plusSeconds(60), null, nothing)),
        ).compare(listOf(StateItem.TIRES))
        assertEquals(emptyList(), comparison.rows)
        assertEquals(listOf(StateItem.TIRES), comparison.notApplicable)
    }

    @Test
    fun `수치를 읽다 실패했으면 경고등이 꺼져 있어도 없음이라고 하지 않는다`() {
        val failed = VehicleState(telltales = emptySet(), unavailable = mapOf(StateItem.TIRES to Unavailable.Failed("TIMEOUT")))
        assertEquals(null, failed.display(StateItem.TIRES))
        val row = tireRow(failed, failed)
        assertEquals(Verdict.UNKNOWN, row.verdict)
        assertEquals(listOf("읽지 못함 (시간 초과)"), row.notes)

        val lit = failed.copy(telltales = setOf(tpmsLamp))
        assertEquals("공기압 경고등 켜짐", lit.display(StateItem.TIRES))
    }

    @Test
    fun `주행거리가 줄었으면 변화 없음으로 두지 않는다`() {
        val row = Inspector(Rental.rules()).inspect(
            InspectionContext(
                VehicleSnapshot(t0, null, VehicleState(odometerKm = 24_224.0)),
                VehicleSnapshot(t0.plusSeconds(60), null, VehicleState(odometerKm = 0.0)),
            ),
        ).compare(listOf(StateItem.ODOMETER)).rows.single()
        assertEquals(Verdict.NEEDS_CHECK, row.verdict)
        assertEquals(listOf("주행거리 줄어듦: 24224 → 0km"), row.notes)
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
    fun `대여 중에 구독하는 항목은 남길 수 있는 항목뿐이다`() {
        assertEquals(
            setOf(StateItem.TELLTALES, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.AIRBAGS),
            Rental.WATCHED_DURING_RENTAL,
        )
        assertEquals(false, StateItem.DOORS in Rental.WATCHED_DURING_RENTAL)
        assertEquals(false, StateItem.ODOMETER in Rental.WATCHED_DURING_RENTAL)
    }

    @Test
    fun `대여 중에는 차의 상태 변화만 남긴다`() {
        assertTrue(Rental.recordsDuringRental(VehicleEvent.TelltalesChanged(emptySet())))
        assertTrue(Rental.recordsDuringRental(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)))
        assertTrue(Rental.recordsDuringRental(VehicleEvent.AirbagChanged(Area.FRONT_LEFT, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.DoorChanged(Area.FRONT_LEFT, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.SeatOccupancyChanged(Area.FRONT_RIGHT, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.ClosureChanged(StateItem.TRUNK, true)))
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.LevelChanged(StateItem.ODOMETER, 100.0)))
    }

    @Test
    fun `인수 때 읽지 못한 값은 고객에게 불리하게 경고로 올리지 않는다`() {
        val after = VehicleState(
            telltales = setOf(Telltale("ENGINE", "엔진 점검")),
            engineOil = FluidLevel.LOW,
            tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = true)),
            airbagsDeployed = mapOf(Area.FRONT_LEFT to true),
        )
        val result = findings(VehicleState(), after)

        assertEquals(
            listOf(
                Finding("telltales", Severity.INFO, "경고등 켜짐 (인수 때 확인 못 함): 엔진 점검", StateItem.TELLTALES),
                Finding("tires", Severity.INFO, "앞 왼쪽 타이어 공기압 낮음 (인수 때 확인 못 함)", StateItem.TIRES),
                Finding("airbags", Severity.INFO, "에어백 전개 기록 (인수 때 확인 못 함): 운전석", StateItem.AIRBAGS),
                Finding("fluids", Severity.INFO, "엔진오일 부족 (인수 때 확인 못 함)", StateItem.ENGINE_OIL),
            ).sortedBy { it.ruleId },
            result.sortedBy { it.ruleId },
        )
        val report = Inspector(Rental.rules()).inspect(
            InspectionContext(VehicleSnapshot(t0, null, VehicleState()), VehicleSnapshot(t0.plusSeconds(60), null, after)),
        )
        val telltales = report.compare(listOf(StateItem.TELLTALES)).rows.single()
        assertEquals(Verdict.NOTE, telltales.verdict, "확인 필요가 아니라 참고다")
    }

    @Test
    fun `기준값을 못 읽은 타이어는 저압 없음이라고 하지 않는다`() {
        val unknown = VehicleState(tires = mapOf(Area.FRONT_LEFT to TireState(pressureKpa = 230.0)))
        val known = VehicleState(tires = mapOf(Area.FRONT_LEFT to TireState(pressureKpa = 230.0, lowPressure = false)))
        assertEquals("저압 확인 못 함", unknown.display(StateItem.TIRES))
        assertEquals("저압 없음", known.display(StateItem.TIRES))
    }

    @Test
    fun `금지어는 고정 안내 문구에서만 허용한다`() {
        assertEquals(emptyList(), Wording.violations("참고 정산(업체 기준): 약 9.9L · 16,800원 · 청구 확정은 업체"))
        assertEquals(emptyList(), Wording.violations("참고 정산(업체 기준): 약 16.3kWh · 5,700원 · 청구 확정은 업체"))
        assertEquals(emptyList(), Wording.violations(RentalReport.DISCLAIMER))
        assertEquals(listOf("책임", "청구"), Wording.violations("고객 책임으로 청구합니다"))
    }

    @Test
    fun `렌터카 규칙이 내는 모든 문구에 금지어가 없다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val all = Area.entries.associateWith { true }
        val before = VehicleState(
            fuelPercent = 82.0, evSocPercent = 90.0, odometerKm = 100.0, tripSinceRefillKm = 300.0,
            engineOil = FluidLevel.LOW, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW,
            telltales = setOf(engine, Telltale("ABS", "ABS")), tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = false)),
            airbagsDeployed = mapOf(Area.FRONT_RIGHT to true, Area.FRONT_LEFT to false),
        )
        val after = VehicleState(
            fuelPercent = 41.0, evSocPercent = 60.0, odometerKm = 412.0, tripSinceRefillKm = 20.0,
            engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.LOW, washerFluid = FluidLevel.LOW,
            telltales = setOf(engine, Telltale("TPMS", "타이어 공기압")), tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = true)),
            airbagsDeployed = all, doorsOpen = all, windowsOpen = all, headlightsOn = true, tailLampOn = true, cabinLightOn = true,
            trunkOpen = true, chargePortOpen = true,
        )
        val messages = findings(
            before, after, RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700, batteryCapacityKwh = 77.4, electricityPricePerKwh = 350),
        ).map { it.message }

        assertTrue(messages.size >= 15, messages.toString())
        messages.forEach { assertEquals(emptyList(), Wording.violations(it), it) }
    }

    @Test
    fun `대여 중 기록이 비었다는 문구는 이 차가 읽은 항목만 말한다`() {
        val everything = VehicleState(
            telltales = emptySet(), engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.NORMAL,
            airbagsDeployed = mapOf(Area.FRONT_LEFT to false),
        )
        val text = Rental.timelineEmpty(everything)
        assertEquals("경고등·소모품·에어백 변화가 기록되지 않았습니다.", text)
        assertEquals("지금까지 경고등·소모품·에어백 변화가 없습니다", Rental.timelineQuiet(everything))
        assertEquals(emptyList(), Wording.violations(text), text)
        // 주행과 잔량은 대여 중 기록에 들지 않는다. 리포트에 '주행 338km'가 있어도 어긋나지 않아야 한다.
        assertEquals(false, Rental.recordsDuringRental(VehicleEvent.LevelChanged(StateItem.ODOMETER, 100.0)))
        assertEquals(false, "주행" in text || "대여 중 변화" in text, text)
    }

    @Test
    fun `고지와 알림 문구는 금지어 없이 위치와 운전 행동을 기록하지 않는다고 밝힌다`() {
        listOf(Rental.NOTICE_DURING_RENTAL, Rental.RECORDING_TEXT).forEach { text ->
            assertEquals(emptyList(), Wording.violations(text), text)
            assertTrue("위치와 운전 행동은 기록하지 않습니다" in text, text)
            assertTrue("경고등" in text, text)
            assertEquals(false, "문 " in text, "문 열림은 남기지 않으므로 말하지 않는다: $text")
        }
    }

    @Test
    fun `전기차에 없는 소모품과 에어백은 변화 없음으로 말하지 않는다`() {
        val missing = listOf(StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.AIRBAGS)
        val ev = VehicleState(telltales = emptySet(), unavailable = missing.associateWith { Unavailable.Unsupported })

        assertEquals("경고등 변화가 기록되지 않았습니다.", Rental.timelineEmpty(ev))
        assertEquals("지금까지 경고등 변화가 없습니다", Rental.timelineQuiet(ev))
        listOf(Rental.timelineEmpty(ev), Rental.timelineQuiet(ev)).forEach { text ->
            listOf("소모품", "에어백", "엔진오일").forEach { assertEquals(false, it in text, text) }
        }
    }

    @Test
    fun `읽다 실패한 항목은 변화 없음이 아니라 알 수 없다고 말한다`() {
        val failed = VehicleState(
            washerFluid = FluidLevel.NORMAL,
            unavailable = mapOf(
                StateItem.TELLTALES to Unavailable.Failed("PERMISSION"), StateItem.ENGINE_OIL to Unavailable.Unsupported,
                StateItem.BRAKE_FLUID to Unavailable.Unsupported, StateItem.AIRBAGS to Unavailable.Unsupported,
            ),
        )
        assertEquals("워셔액 변화가 기록되지 않았습니다. 경고등은 인수 때 읽지 못해 변화를 알 수 없습니다.", Rental.timelineEmpty(failed))
        assertEquals("지금까지 워셔액 변화가 없습니다  ·  경고등 읽지 못함", Rental.timelineQuiet(failed))

        val nothing = VehicleState(unavailable = (Rental.WATCHED_DURING_RENTAL).associateWith { Unavailable.Unsupported })
        assertEquals("이 차에서 대여 중에 읽는 항목이 없습니다.", Rental.timelineEmpty(nothing))
        assertEquals("이 차에서 대여 중에 읽는 항목이 없습니다", Rental.timelineQuiet(nothing))
        assertEquals("이 차에서 대여 중에 읽는 항목이 없습니다", Rental.timelineQuiet(null))
    }

    @Test
    fun `고지 문구는 어느 차에서 읽어도 참이다`() {
        listOf(Rental.NOTICE_DURING_RENTAL, Rental.RECORDING_TEXT, Rental.NOTICE_KEEP).forEach { text ->
            assertEquals(emptyList(), Wording.violations(text), text)
            listOf("엔진오일", "에어백", "브레이크액").forEach { assertEquals(false, it in text, text) }
        }
        assertTrue("이 차가 알려 주는" in Rental.NOTICE_DURING_RENTAL)
        assertTrue("${Rental.RETENTION_DAYS}일" in Rental.NOTICE_KEEP)
    }

    @Test
    fun `시연 단가는 시연 단가라고 부르고 실제 업체 기준은 업체 기준이라고 부른다`() {
        val demo = RentalPolicy(version = "demo/2", batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)
        val real = RentalPolicy(version = "lotte/1", batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)
        assertEquals(true, demo.demo)
        assertEquals(false, real.demo)
        assertEquals(false, RentalPolicy().demo)

        val before = VehicleState(evSocPercent = 82.0)
        val after = VehicleState(evSocPercent = 61.0)
        assertEquals("참고 정산(시연 단가): 약 16.3kWh · 5,700원 · 청구 확정은 업체", findings(before, after, demo).single { it.ruleId == "battery_settlement" }.message)
        assertEquals("참고 정산(업체 기준): 약 16.3kWh · 5,700원 · 청구 확정은 업체", findings(before, after, real).single { it.ruleId == "battery_settlement" }.message)

        assertEquals("참고 정산(시연 단가) 약 5,700원 · 청구 확정은 업체", ReturnCheck.energy(before, after, demo)?.settlementText)
        assertEquals("참고 정산(업체 기준) 약 5,700원 · 청구 확정은 업체", ReturnCheck.energy(before, after, real)?.settlementText)
        assertEquals("참고 정산(시연 단가) 약 5,700원 · 청구 확정은 업체", ReturnTrip.plan(before, 61, demo)?.gauge?.settlementText)
        assertEquals(emptyList(), Wording.violations("참고 정산(시연 단가): 약 16.3kWh · 5,700원 · 청구 확정은 업체"))
    }
}
