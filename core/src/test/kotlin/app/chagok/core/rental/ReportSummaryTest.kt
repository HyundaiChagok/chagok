package app.chagok.core.rental

import app.chagok.core.inspect.Verdict
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 리포트의 결론, TPMS 한 줄, 주행 0km, 인수 때부터, 직원 기록 */
class ReportSummaryTest {
    private val tpms = Telltale(Telltale.TPMS, "타이어 공기압")
    private val engine = Telltale("ENGINE", "엔진 점검")
    private val policy = RentalPolicy(version = "t/1", batteryCapacityKwh = 77.4, electricityPricePerKwh = 350, tankCapacityL = 47.0, fuelPricePerL = 1_700)
    private val closed = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT).associateWith { false }

    // 전기차 에뮬레이터처럼 공기압 수치가 없어 타이어 칸의 근거가 TPMS 경고등뿐인 차
    private val ev = VehicleState(
        evSocPercent = 82.0, evCapacityKwh = 77.4, odometerKm = 23_410.0, telltales = emptySet(), doorsOpen = closed, windowsOpen = closed, trunkOpen = false,
        unavailable = mapOf(
            StateItem.TIRES to Unavailable.Unsupported, StateItem.FUEL to Unavailable.Unsupported, StateItem.AIRBAGS to Unavailable.Unsupported,
            StateItem.ENGINE_OIL to Unavailable.Unsupported, StateItem.BRAKE_FLUID to Unavailable.Unsupported, StateItem.WASHER_FLUID to Unavailable.Unsupported,
            StateItem.CHARGE_PORT to Unavailable.Unsupported, StateItem.HEADLIGHTS to Unavailable.Unsupported, StateItem.TAIL_LAMP to Unavailable.Unsupported,
            StateItem.CABIN_LIGHT to Unavailable.Unsupported,
        ),
    )
    private val pickedUp = Instant.parse("2026-11-01T05:50:00Z")
    private val returned = Instant.parse("2026-11-04T03:00:00Z")

    private fun record(kind: SnapshotKind, at: Instant, state: VehicleState) = SnapshotRecord(
        sessionId = "s-1", recordNo = "0007-1101-A1", kind = kind, vehicleId = "V-0007", capturedAt = at,
        state = state.forRecord(Rental.RECORDED), items = Rental.RECORDED, appVersion = "0.1.0", rulesVersion = Rental.RULES_VERSION,
    )

    private fun report(before: VehicleState, after: VehicleState, recordedBy: String? = null) =
        RentalReport.build(record(SnapshotKind.PICKUP, pickedUp, before), record(SnapshotKind.RETURN, returned, after), policy, recordedBy = recordedBy)

    private fun RentalReport.row(item: StateItem) = comparison.rows.firstOrNull { it.item == item }

    @Test
    fun `TPMS가 켜지면 타이어 줄에서 한 번만 말해 확인 필요가 잔량과 타이어 두 건이다`() {
        val report = report(ev, ev.copy(evSocPercent = 61.0, telltales = setOf(tpms)))
        assertEquals(2, report.comparison.count(Verdict.NEEDS_CHECK))
        assertEquals(listOf("타이어 공기압 경고등 켜짐"), report.row(StateItem.TIRES)?.notes)
        assertNull(report.row(StateItem.TELLTALES), "'경고등 변화 없음'이라고 적으면 타이어 줄과 어긋난다")
        assertEquals(2, report.toJson().getValue("counts").jsonObject.getValue("NEEDS_CHECK").jsonPrimitive.content.toInt())
    }

    @Test
    fun `다른 경고등과 함께 켜지면 경고등 줄에는 그 경고등만 적는다`() {
        val report = report(ev, ev.copy(telltales = setOf(tpms, engine)))
        val telltales = report.row(StateItem.TELLTALES)!!
        assertEquals(listOf("경고등 켜짐: 엔진 점검"), telltales.notes)
        assertEquals("없음", telltales.before)
        assertEquals("엔진 점검", telltales.after)
        assertEquals(listOf("타이어 공기압 경고등 켜짐"), report.row(StateItem.TIRES)?.notes)
        assertEquals(2, report.comparison.count(Verdict.NEEDS_CHECK))
    }

    @Test
    fun `바퀴 저압과 TPMS가 함께 나타나도 한 줄이다`() {
        val fine = mapOf(Area.FRONT_LEFT to TireState(lowPressure = false))
        val low = mapOf(Area.FRONT_LEFT to TireState(lowPressure = true))
        val before = ev.copy(tires = fine, unavailable = ev.unavailable - StateItem.TIRES)
        val report = report(before, before.copy(tires = low, telltales = setOf(tpms)))
        assertEquals(listOf("앞 왼쪽 타이어 공기압 낮음"), report.row(StateItem.TIRES)?.notes)
        assertNull(report.row(StateItem.TELLTALES))
        assertEquals(1, report.comparison.count(Verdict.NEEDS_CHECK))
    }

    @Test
    fun `TPMS가 꺼지면 타이어 줄에서 한 번만 말한다`() {
        val report = report(ev.copy(telltales = setOf(tpms)), ev)
        assertEquals(listOf("타이어 공기압 경고등 꺼짐"), report.row(StateItem.TIRES)?.notes)
        assertNull(report.row(StateItem.TELLTALES))
    }

    @Test
    fun `인수 때부터 켜진 TPMS는 방패 한 줄이고 경고등 줄은 변화 없음이다`() {
        val lit = ev.copy(telltales = setOf(tpms))
        val report = report(lit, lit)
        assertEquals(listOf(PickupFact(StateItem.TIRES, "타이어 공기압 경고등 켜짐")), report.fromPickup)
        val telltales = report.row(StateItem.TELLTALES)!!
        assertEquals(Verdict.UNCHANGED, telltales.verdict)
        assertEquals("없음", telltales.after)
        assertEquals(0, report.comparison.count(Verdict.NEEDS_CHECK))
    }

    @Test
    fun `인수 때부터 있던 경고등은 인수 시각과 함께 지켜 주는 문장이 된다`() {
        val lit = ev.copy(telltales = setOf(engine))
        val report = report(lit, lit.copy(telltales = setOf(engine, Telltale("ABS", "ABS"))))
        assertEquals(listOf(PickupFact(StateItem.TELLTALES, "경고등 켜짐: 엔진 점검")), report.fromPickup)
        assertEquals("이 경고등은 11.01 14:50 인수 기록에 있습니다.", report.fromPickup.single().recordedAt("11.01 14:50"))
        assertEquals("이 항목은 11.01 14:50 인수 기록에 있습니다.", PickupFact(StateItem.WASHER_FLUID, "워셔액 부족").recordedAt("11.01 14:50"))
        assertEquals(Verdict.NEEDS_CHECK, report.row(StateItem.TELLTALES)?.verdict, "새로 켜진 경고등이 있으면 확인 필요다")
    }

    @Test
    fun `주행 0km는 참고가 아니라 변화 없음이다`() {
        val still = report(ev, ev)
        assertEquals(Verdict.UNCHANGED, still.row(StateItem.ODOMETER)?.verdict)
        assertEquals(emptyList(), still.row(StateItem.ODOMETER)?.notes)
        val creep = report(ev, ev.copy(odometerKm = 23_410.04))
        assertEquals(Verdict.UNCHANGED, creep.row(StateItem.ODOMETER)?.verdict, "화면에 0km로 보일 거리다")
        assertEquals(listOf("주행 312km"), report(ev, ev.copy(odometerKm = 23_722.0)).row(StateItem.ODOMETER)?.notes)
    }

    @Test
    fun `결론은 고객 웹과 같은 문장이다`() {
        val dropped = report(ev, ev.copy(evSocPercent = 61.0))
        assertEquals("배터리 82% → 61%, 참고 정산 약 5,700원입니다.", dropped.conclusionText)
        assertEquals(listOf("배터리 *82% → 61%*,", "참고 정산 *약 5,700원*입니다."), dropped.conclusion)

        val fuel = VehicleState(fuelPercent = 82.0, odometerKm = 1.0, telltales = emptySet(), unavailable = mapOf(StateItem.EV_SOC to Unavailable.Unsupported))
        assertEquals("연료 82% → 61%, 참고 정산 약 16,800원입니다.", report(fuel, fuel.copy(fuelPercent = 61.0)).conclusionText)

        assertEquals("배터리 82% 그대로, 확인 필요한 항목이 없습니다.", report(ev, ev).conclusionText)
        assertEquals("배터리 82% 그대로, 확인 필요 1건입니다.", report(ev, ev.copy(trunkOpen = true)).conclusionText)
        assertEquals("배터리 82% → 80%, 확인 필요한 항목이 없습니다.", report(ev, ev.copy(evSocPercent = 80.0)).conclusionText)

        val noEnergy = ev.copy(evSocPercent = null, unavailable = ev.unavailable + (StateItem.EV_SOC to Unavailable.Unsupported))
        assertEquals("확인 필요한 항목이 없습니다.", report(noEnergy, noEnergy).conclusionText)
    }

    @Test
    fun `잔량 줄에는 군더더기를 적지 않는다`() {
        assertEquals("배터리 82% → 61%", report(ev, ev.copy(evSocPercent = 61.0)).row(StateItem.EV_SOC)?.notes?.first())
    }

    @Test
    fun `직원 기록은 무결성 값에 묶이고 되살려도 그대로다`() {
        val after = ev.copy(evSocPercent = 61.0)
        val customer = report(ev, after)
        val staff = report(ev, after, recordedBy = RentalReport.STAFF)
        assertFalse(customer.byStaff)
        assertTrue(staff.byStaff)
        assertFalse("recordedBy" in customer.toJson(), "없으면 키도 없어 이전 리포트의 무결성 값이 그대로다")
        assertEquals("staff", staff.toJson().getValue("recordedBy").jsonPrimitive.content)
        assertNotEquals(customer.hash, staff.hash)
        assertTrue("직원 기록" in staff.toText())
        assertFalse("직원 기록" in customer.toText())

        val saved = Json.parseToJsonElement(Canonical.encode(JsonObject(staff.toJson() + ("hash" to JsonPrimitive(staff.hash))))).jsonObject
        val restored = RentalReport.fromJson(saved, staff.pickup, staff.returned)
        assertEquals(staff.hash, restored.hash)
        assertTrue(restored.byStaff)

        // 고객 웹으로 가는 데이터 QR에도 그대로 간다.
        val shared = Json.parseToJsonElement(ShareCode.decode(ShareCode.encode(staff))).jsonObject
        assertEquals("staff", shared.getValue("recordedBy").jsonPrimitive.content)
    }

    @Test
    fun `1분이 안 되는 공백과 대여는 시각 하나에 1분 미만을 붙인다`() {
        val state = VehicleState(fuelPercent = 82.0, unavailable = Rental.RECORDED.filter { it != StateItem.FUEL }.associateWith { Unavailable.Unsupported })
        val at = java.time.Instant.parse("2026-11-03T03:40:50Z")
        fun record(kind: SnapshotKind, time: java.time.Instant) = SnapshotRecord(
            sessionId = "s", recordNo = "0007-1103-A1", kind = kind, vehicleId = "V-0007", capturedAt = time,
            state = state, items = Rental.RECORDED, appVersion = "t", rulesVersion = Rental.RULES_VERSION,
        )
        val short = RecordGap(at, at.plusSeconds(20))
        val long = RecordGap(at.plusSeconds(600), at.plusSeconds(900))

        val text = RentalReport.build(record(SnapshotKind.PICKUP, at), record(SnapshotKind.RETURN, at.plusSeconds(3600)), gaps = listOf(short, long)).toText()
        assertTrue("- 2026-11-03 12:40 (1분 미만)" in text, text)
        assertTrue("- 2026-11-03 12:50 → 2026-11-03 12:55" in text, text)

        val quick = RentalReport.build(record(SnapshotKind.PICKUP, at), record(SnapshotKind.RETURN, at.plusSeconds(30))).toText()
        assertTrue("2026-11-03 12:40 (1분 미만)" in quick, quick)
        assertEquals(false, "→" in quick.lines()[1], quick)

        // 분이 넘어가도 1분이 안 되면 같은 말이다. 정확히 1분부터는 두 시각을 적는다.
        assertEquals(true, Rental.underAMinute(at, at.plusSeconds(59)))
        assertEquals(false, Rental.underAMinute(at, at.plusSeconds(60)))
        assertEquals(false, Rental.underAMinute(at, at.minusSeconds(5)))
    }

    @Test
    fun `시연 단가로 만든 리포트의 결론은 시연 단가라고 밝힌다`() {
        val before = VehicleState(evSocPercent = 82.0, evCapacityKwh = 77.4, unavailable = Rental.RECORDED.filter { it != StateItem.EV_SOC && it != StateItem.EV_CAPACITY }.associateWith { Unavailable.Unsupported })
        fun record(kind: SnapshotKind, percent: Double) = SnapshotRecord(
            sessionId = "s", recordNo = "0007-1103-A1", kind = kind, vehicleId = "V-0007", capturedAt = java.time.Instant.parse("2026-11-03T03:40:00Z"),
            state = before.copy(evSocPercent = percent), items = Rental.RECORDED, appVersion = "t", rulesVersion = Rental.RULES_VERSION,
        )
        val demo = RentalReport.build(record(SnapshotKind.PICKUP, 82.0), record(SnapshotKind.RETURN, 61.0), RentalPolicy(version = "demo/2", electricityPricePerKwh = 350))
        assertEquals(listOf("배터리 *82% → 61%*,", "참고 정산(시연 단가) *약 5,700원*입니다."), demo.conclusion)
        assertTrue("참고 정산(시연 단가): 약 16.3kWh · 5,700원" in demo.toText(), demo.toText())

        val real = RentalReport.build(record(SnapshotKind.PICKUP, 82.0), record(SnapshotKind.RETURN, 61.0), RentalPolicy(version = "lotte/1", electricityPricePerKwh = 350))
        assertEquals(listOf("배터리 *82% → 61%*,", "참고 정산 *약 5,700원*입니다."), real.conclusion)
        assertTrue("참고 정산(업체 기준): 약 16.3kWh · 5,700원" in real.toText(), real.toText())
    }
}
