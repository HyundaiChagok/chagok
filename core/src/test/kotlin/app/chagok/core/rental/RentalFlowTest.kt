package app.chagok.core.rental

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.driven
import app.chagok.core.fake.withDoor
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withLowTire
import app.chagok.core.fake.withTelltale
import app.chagok.core.fake.withWindow
import app.chagok.core.inspect.Verdict
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.session.SessionRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 차 없이 한 번의 대여를 처음부터 끝까지 돌린다(요구사항 TER-02). 공통 모듈을 이어 붙이는 순서의 예시이기도 하다. */
@OptIn(ExperimentalCoroutinesApi::class)
class RentalFlowTest {
    private val fourSeats = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.REAR_LEFT, Area.REAR_RIGHT)

    private val avante = VehicleState(
        fuelPercent = 82.0, odometerKm = 23_410.0, tripSinceRefillKm = 210.0,
        doorsOpen = fourSeats.associateWith { false }, windowsOpen = fourSeats.associateWith { false }, trunkOpen = false,
        headlightsOn = false, tailLampOn = false,
        seatsOccupied = mapOf(Area.FRONT_LEFT to true),
        engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW,
        telltales = emptySet(),
        tires = fourSeats.associateWith { TireState(lowPressure = false) },
        airbagsDeployed = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to false),
        unavailable = mapOf(
            StateItem.EV_SOC to Unavailable.Unsupported,
            StateItem.EV_CHARGING to Unavailable.Unsupported,
            StateItem.EV_CAPACITY to Unavailable.Unsupported,
            StateItem.CHARGE_PORT to Unavailable.Unsupported,
            StateItem.CABIN_LIGHT to Unavailable.Unsupported,
        ),
    )

    // Pleos 에뮬레이터에서 실제로 읽히는 값의 모양(2026.11.02 확인). 공기압 수치와 소모품·에어백은 차량 서비스에 없다.
    private val ioniq = VehicleState(
        evSocPercent = 82.0, evCharging = false, evCapacityKwh = 77.4, odometerKm = 23_410.0,
        doorsOpen = fourSeats.associateWith { false }, windowsOpen = fourSeats.associateWith { false },
        trunkOpen = false, chargePortOpen = false, headlightsOn = false, tailLampOn = false, cabinLightOn = false,
        telltales = emptySet(),
        unavailable = listOf(
            StateItem.FUEL, StateItem.TRIP_SINCE_REFILL, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
            StateItem.AIRBAGS, StateItem.TIRES,
        ).associateWith { Unavailable.Unsupported },
    )

    @Test
    fun `인수부터 반납 리포트와 데이터 QR까지`() = runTest {
        val vehicle = FakeVehicle(avante)
        var clock = Instant.parse("2026-11-03T00:12:00Z") // 09:12 KST
        val recorder = RentalRecorder(vehicle, vehicleId = "V-0007", appVersion = "0.1.0") { clock }
        val recordNo = RecordNumber.of("7", LocalDate.of(2026, 11, 3), 0)

        // 인수
        val pickup = recorder.capture("s-1", recordNo, SnapshotKind.PICKUP)
        assertEquals(emptyMap(), pickup.state.seatsOccupied, "좌석 착석은 기록하지 않는다")
        assertEquals(
            "인수 기록을 남겼습니다. 연료 82퍼센트, 켜진 경고등은 없습니다. 화면의 QR로 같은 기록을 받으세요.",
            Voice.pickupDone(pickup.state),
        )

        // 대여 중: 312km를 달리며 연료가 21%p 줄고 앞 왼쪽 타이어 저압 경고가 켜졌다. 문 열림은 남기지 않는다.
        val timeline = SessionRecorder(vehicle) { clock }.start(backgroundScope) { Rental.recordsDuringRental(it) }
        vehicle.update { driven(km = 312.0, fuelUsedPercent = 21.0).withLowTire(Area.FRONT_LEFT).withDoor(Area.FRONT_LEFT, true) }
        runCurrent()
        clock = Instant.parse("2026-11-05T08:40:00Z") // 17:40 KST

        // 반납 체크: 조수석 창문과 미등을 놓쳤다가 고친다
        vehicle.update { withDoor(Area.FRONT_LEFT, false).withWindow(Area.FRONT_RIGHT, true).withLight(StateItem.TAIL_LAMP, true) }
        val check = ReturnCheck.items(pickup.state, vehicle.state())
        assertEquals(
            "반납 전에 2가지를 확인해 주세요. 조수석 창문이 열려 있고, 미등이 켜져 있습니다. 연료는 인수 때 82퍼센트, 지금 61퍼센트입니다.",
            Voice.returnCheck(check),
        )
        vehicle.update { withWindow(Area.FRONT_RIGHT, false).withLight(StateItem.TAIL_LAMP, false) }
        assertEquals(listOf(CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)), ReturnCheck.items(pickup.state, vehicle.state()))

        // 반납 기록과 리포트
        val returned = recorder.capture("s-1", recordNo, SnapshotKind.RETURN)
        val during = timeline.finish().events
        assertEquals(emptyList(), during, "대여 중 기록에 문 열림이 남지 않는다")
        val report = RentalReport.build(pickup, returned, RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700), during)
        assertEquals(
            """
            반납 리포트 · 0007-1103-A1
            2026-11-03 09:12 → 2026-11-05 17:40 (2일 8시간 28분)
            확인 필요 2 · 참고 2 · 변화 없음 9 · 확인 불가 0
            ${RentalReport.DISCLAIMER}

            확인 필요
            - 연료 82% → 61%
            - 참고 정산(업체 기준): 약 9.9L · 16,800원 · 청구 확정은 업체
            - 앞 왼쪽 타이어 공기압 낮음

            참고
            - 주행 312km
            - 워셔액 부족 (인수 때부터)

            변화 없음
            경고등, 에어백, 엔진오일, 브레이크액, 문, 트렁크, 창문, 전조등, 미등

            무결성 ${Canonical.short(report.hash)}
            """.trimIndent(),
            report.toText(),
        )
        assertEquals(emptyList(), Wording.violations(report.toText()))

        // 저장했다 다시 읽어도 같은 리포트가 나온다(서버의 재계산과 같은 일)
        val reloaded = listOf(pickup, returned).map {
            SnapshotRecord.fromJson(Json.parseToJsonElement(Canonical.encode(it.toJsonWithHash())).jsonObject)
        }
        assertEquals(report.hash, RentalReport.build(reloaded[0], reloaded[1], report.policy).hash)

        // 데이터 QR: 풀어낸 문자열의 SHA-256이 곧 리포트 무결성 값이다
        val code = ShareCode.encode(report)
        val bytes = Base64.getUrlDecoder().decode(code.substringAfter('.'))
        assertTrue(bytes.size <= ShareCode.MAX_BYTES, "${bytes.size}바이트")
        assertEquals(report.hash, Canonical.sha256(ShareCode.decode(code)))
        assertTrue(ShareCode.url("https://example.com/chagok/", report).startsWith("https://example.com/chagok/r#d=1."))
        // 인수 기록도 같은 방식으로 넘긴다
        assertEquals(pickup.hash, Canonical.sha256(ShareCode.decode(ShareCode.encode(pickup))))
    }

    @Test
    fun `전기차 대여는 없는 칸 없이 실제 값으로만 리포트가 나오고 전체가 QR에 들어간다`() = runTest {
        val vehicle = FakeVehicle(ioniq)
        var clock = Instant.parse("2026-11-03T00:12:00.123456Z")
        val recorder = RentalRecorder(vehicle, vehicleId = "V-0007", appVersion = "0.1.0", source = SnapshotRecord.SOURCE_EMULATOR) { clock }
        val policy = RentalPolicy(version = "demo/2", batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)

        val pickup = recorder.capture("0b6f3d4e-7a1c-4a55-9c2e-5d0c8f1e2a3b", "0007-1103-A1", SnapshotKind.PICKUP)
        assertEquals("인수 기록을 남겼습니다. 배터리 82퍼센트, 켜진 경고등은 없습니다. 화면의 QR로 같은 기록을 받으세요.", Voice.pickupDone(pickup.state))
        assertTrue(qrBytes(ShareCode.encode(pickup)) <= ShareCode.MAX_BYTES)

        vehicle.update {
            copy(odometerKm = 23_722.0, evSocPercent = 61.0, windowsOpen = windowsOpen + (Area.FRONT_RIGHT to true), trunkOpen = true, cabinLightOn = true)
                .withTelltale(tpms)
        }
        clock = Instant.parse("2026-11-05T08:40:00.654321Z")
        val returned = recorder.capture(pickup.sessionId, pickup.recordNo, SnapshotKind.RETURN)
        val lit = Instant.parse("2026-11-04T01:02:03.456Z")
        val report = RentalReport.build(
            pickup, returned, policy,
            events = listOf(LoggedEvent(lit, null, VehicleEvent.TelltalesChanged(setOf(tpms)))),
            gaps = listOf(RecordGap(Instant.parse("2026-11-03T22:00:00Z"), Instant.parse("2026-11-04T00:30:00Z"))),
        )

        assertEquals(
            listOf(StateItem.FUEL, StateItem.AIRBAGS, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID),
            report.comparison.notApplicable,
        )
        assertEquals(
            listOf(
                StateItem.EV_SOC, StateItem.ODOMETER, StateItem.TIRES, StateItem.DOORS, StateItem.TRUNK,
                StateItem.CHARGE_PORT, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
            ).sorted(),
            report.comparison.rows.map { it.item }.sorted(),
        )
        assertEquals(0, report.comparison.count(Verdict.UNKNOWN), "실패가 없으면 확인 불가도 없다")
        val battery = report.comparison.rows.first { it.item == StateItem.EV_SOC }
        assertEquals(listOf("배터리 82% → 61%", "참고 정산(시연 단가): 약 16.3kWh · 5,700원 · 청구 확정은 업체"), battery.notes)
        assertEquals("공기압 경고등 켜짐", report.comparison.rows.first { it.item == StateItem.TIRES }.after)

        val code = ShareCode.encode(report)
        val json = decoded(code)
        assertEquals("report/2", json.getValue("schema").jsonPrimitive.content, "요약본으로 떨어지지 않는다")
        assertEquals(report.hash, Canonical.sha256(ShareCode.decode(code)))
        assertTrue(qrBytes(code) <= ShareCode.MAX_BYTES, "${qrBytes(code)}바이트")
        assertEquals("emulator", json.getValue("source").jsonPrimitive.content)
        assertEquals(true, json.getValue("noVin").jsonPrimitive.content.toBoolean())
        report.toText().let { text ->
            assertEquals(emptyList(), Wording.violations(text), text)
            assertTrue("에뮬레이터 값" in text, text)
        }
    }

    @Test
    fun `경고등이 여럿 켜져 요약본도 넘치면 잔량 말고 줄의 값을 빼서라도 QR을 만든다`() {
        val many = setOf(
            tpms, Telltale("ENGINE", "엔진 점검"), Telltale("ABS", "ABS"), Telltale("ESC", "차체 자세 제어(ESC)"), Telltale("EV_SYSTEM", "전기차 시스템"),
        )
        val after = ioniq.copy(
            evSocPercent = 61.0, odometerKm = 23_722.0, telltales = many, trunkOpen = true, cabinLightOn = true,
            windowsOpen = fourSeats.associateWith { it == Area.FRONT_RIGHT },
        )
        val session = "0b6f3d4e-7a1c-4a55-9c2e-5d0c8f1e2a3b"
        val events = many.mapIndexed { i, t -> LoggedEvent(t0.plusSeconds(600L * (i + 1)), null, VehicleEvent.TelltalesChanged(setOf(t))) }
        val report = RentalReport.build(
            record(SnapshotKind.PICKUP, pickupAt, ioniq).copy(sessionId = session),
            record(SnapshotKind.RETURN, returnAt, after).copy(sessionId = session),
            RentalPolicy(version = "demo/2", batteryCapacityKwh = 77.4, electricityPricePerKwh = 350),
            events = events,
            gaps = listOf(RecordGap(t0.plusSeconds(60), t0.plusSeconds(120))),
        )

        val code = ShareCode.encode(report)
        val json = decoded(code)
        assertTrue(qrBytes(code) <= ShareCode.MAX_BYTES, "${qrBytes(code)}바이트")
        assertEquals("report-summary/2", json.getValue("schema").jsonPrimitive.content)
        assertEquals(report.hash, json.getValue("reportHash").jsonPrimitive.content)
        val rows = json.getValue("rows").jsonArray.map { it.jsonObject }
        val battery = rows.first { it.getValue("item").jsonPrimitive.content == StateItem.EV_SOC.key }
        assertEquals("82%", battery.getValue("before").jsonPrimitive.content, "결론 문장에 쓰는 잔량 값은 남긴다")
        val lamps = rows.first { it.getValue("item").jsonPrimitive.content == StateItem.TELLTALES.key }
        assertFalse("after" in lamps, "값은 빠져도")
        // TPMS는 타이어 줄이 말한다.
        assertEquals(many.size - 1, lamps.getValue("notes").jsonArray.size, "무엇이 켜졌는지는 문구에 남는다")
        val tires = rows.first { it.getValue("item").jsonPrimitive.content == StateItem.TIRES.key }
        assertEquals("타이어 공기압 경고등 켜짐", tires.getValue("notes").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `인수 때부터 저압인 타이어는 인수 때부터 문구로 고객 웹에 넘어간다`() {
        val low = avante.copy(tires = avante.tires + (Area.FRONT_LEFT to TireState(pressureKpa = 120.0, lowPressure = true)))
        val report = RentalReport.build(record(SnapshotKind.PICKUP, pickupAt, low), record(SnapshotKind.RETURN, returnAt, low), RentalPolicy())
        val row = report.comparison.rows.first { it.item == StateItem.TIRES }
        assertEquals(Verdict.NOTE, row.verdict)
        assertEquals(listOf("앞 왼쪽 타이어 공기압 낮음 (인수 때부터)"), row.notes)
    }

    @Test
    fun `차대번호를 읽으면 기록 식별에 쓰고 못 읽으면 업체 번호로 대신한 사실을 남긴다`() = runTest {
        val vin = "KMHC851HFNU123456"
        val withVin = object : app.chagok.core.source.VehicleSource by FakeVehicle(ioniq) {
            override suspend fun vin(): String = vin
        }
        val read = RentalRecorder(withVin, vehicleId = "V-0007", appVersion = "0.1.0") { t0 }.capture("s-1", "A", SnapshotKind.PICKUP)
        assertEquals(vin, read.vehicleId)
        assertFalse(read.vehicleIdFallback)
        assertFalse("noVin" in read.toJson())

        val fallback = RentalRecorder(FakeVehicle(ioniq), vehicleId = "V-0007", appVersion = "0.1.0") { t0 }.capture("s-1", "A", SnapshotKind.PICKUP)
        assertEquals("V-0007", fallback.vehicleId)
        assertTrue(fallback.vehicleIdFallback)
        assertEquals(fallback, SnapshotRecord.fromJson(Json.parseToJsonElement(Canonical.encode(fallback.toJsonWithHash())).jsonObject))
    }

    private val tpms = Telltale("TPMS", "타이어 공기압")
    private val t0 = Instant.parse("2026-11-03T00:12:00Z")
    private val pickupAt = t0
    private val returnAt = Instant.parse("2026-11-05T08:40:00Z")

    private fun record(kind: SnapshotKind, at: Instant, state: VehicleState = avante) = SnapshotRecord(
        sessionId = "s-1", recordNo = "0007-1103-A1", kind = kind, vehicleId = "V-0007", capturedAt = at,
        state = state.forRecord(Rental.RECORDED), items = Rental.RECORDED, appVersion = "0.1.0", rulesVersion = Rental.RULES_VERSION,
    )

    private fun qrBytes(code: String) = Base64.getUrlDecoder().decode(code.substringAfter('.')).size

    private fun decoded(code: String): JsonObject = Json.parseToJsonElement(ShareCode.decode(code)).jsonObject

    // 대여 중 경고등이 번갈아 켜지고 꺼진 기록
    private fun blinking(times: Int) = List(times) { i ->
        val active = if (i % 2 == 0) setOf(tpms) else emptySet()
        LoggedEvent(t0.plusSeconds(3_600L * (i + 1)), null, VehicleEvent.TelltalesChanged(active))
    }

    @Test
    fun `대여 중 변화와 기록 공백이 리포트에 묶여 고객 웹이 읽는 모양으로 나간다`() = runTest {
        val vehicle = FakeVehicle(avante)
        var clock = t0
        val recorder = RentalRecorder(vehicle, vehicleId = "V-0007", appVersion = "0.1.0") { clock }
        val pickup = recorder.capture("s-1", "0007-1103-A1", SnapshotKind.PICKUP)
        val saved = mutableListOf<LoggedEvent>()
        val session = SessionRecorder(vehicle) { clock }
            .start(backgroundScope, pickup.toSnapshot(), onEvent = { saved += it }) { Rental.recordsDuringRental(it) }
        runCurrent()

        // 경고등이 켜진다. 같은 때 열린 문은 남기지 않는다.
        clock = Instant.parse("2026-11-04T05:05:00.250Z")
        vehicle.update { withTelltale(tpms).withDoor(Area.FRONT_LEFT, true) }
        runCurrent()
        assertEquals(listOf(VehicleEvent.TelltalesChanged(setOf(tpms))), saved.map { it.event }, "바로 저장할 수 있게 알린다")

        clock = returnAt
        vehicle.update { withDoor(Area.FRONT_LEFT, false) }
        val returned = recorder.capture("s-1", "0007-1103-A1", SnapshotKind.RETURN)
        val off = RecordGap(Instant.parse("2026-11-04T09:00:00Z"), Instant.parse("2026-11-04T23:10:00Z"))
        val report = RentalReport.build(pickup, returned, RentalPolicy(), session.finish(returned.toSnapshot()).events, listOf(off))

        assertEquals(listOf(TimelineEntry(Instant.parse("2026-11-04T05:05:00Z"), "경고등 켜짐: 타이어 공기압")), report.timeline)
        assertEquals(0, report.timelineMore)
        assertEquals(listOf(off), report.gaps)
        val json = report.toJson()
        assertEquals("report/2", json.getValue("schema").jsonPrimitive.content)
        assertEquals("""[{"at":"2026-11-04T05:05:00Z","note":"경고등 켜짐: 타이어 공기압"}]""", Canonical.encode(json.getValue("timeline")))
        assertEquals("""[{"from":"2026-11-04T09:00:00Z","to":"2026-11-04T23:10:00Z"}]""", Canonical.encode(json.getValue("gaps")))
        assertFalse("timelineMore" in json)

        val text = report.toText()
        assertTrue(text.contains("- 2026-11-04 14:05 경고등 켜짐: 타이어 공기압"), text)
        assertTrue(text.contains("기록 공백\n- 2026-11-04 18:00 → 2026-11-05 08:10"), text)
        assertEquals(emptyList(), Wording.violations(text))

        // 한도 안이면 풀어낸 문자열의 SHA-256이 그대로 무결성 값이다. 타임라인도 묶여 있다.
        val code = ShareCode.encode(report)
        assertTrue(qrBytes(code) <= ShareCode.MAX_BYTES, "${qrBytes(code)}바이트")
        assertEquals(report.hash, Canonical.sha256(ShareCode.decode(code)))
        assertEquals("report/2", decoded(code).getValue("schema").jsonPrimitive.content)
    }

    @Test
    fun `변화가 없으면 타임라인 키를 넣지 않아 무결성 값이 그대로다`() {
        val report = RentalReport.build(record(SnapshotKind.PICKUP, pickupAt), record(SnapshotKind.RETURN, returnAt), RentalPolicy(), emptyList())
        val json = report.toJson()
        listOf("timeline", "timelineMore", "gaps").forEach { assertFalse(it in json, it) }
        assertEquals(report.hash, RentalReport.build(report.pickup, report.returned, RentalPolicy()).hash)
    }

    @Test
    fun `타임라인은 시간순 5건까지 담고 나머지는 건수로 센다`() {
        val report = RentalReport.build(record(SnapshotKind.PICKUP, pickupAt, avante.copy(telltales = emptySet())), record(SnapshotKind.RETURN, returnAt), RentalPolicy(), blinking(8).reversed())

        assertEquals(RentalReport.MAX_TIMELINE, report.timeline.size)
        assertEquals(report.timeline.sortedBy { it.at }, report.timeline)
        assertEquals(t0.plusSeconds(3_600), report.timeline.first().at)
        assertEquals(3, report.timelineMore)
        assertEquals(3, report.toJson().getValue("timelineMore").jsonPrimitive.int)
        assertEquals(RentalReport.MAX_TIMELINE, report.toJson().getValue("timeline").jsonArray.size)
    }

    @Test
    fun `QR 한도를 넘으면 요약본에서 타임라인을 빼고 건수만 남긴다`() {
        val driven = avante.driven(km = 312.0, fuelUsedPercent = 21.0).withLowTire(Area.FRONT_LEFT)
            .withWindow(Area.FRONT_RIGHT, true).withLight(StateItem.TAIL_LAMP, true).withLight(StateItem.HEADLIGHTS, true)
            .withDoor(Area.REAR_RIGHT, true).withTelltale(Telltale("ENGINE", "엔진 점검"))
        val off = RecordGap(t0.plusSeconds(100), t0.plusSeconds(200))
        val report = RentalReport.build(
            record(SnapshotKind.PICKUP, pickupAt), record(SnapshotKind.RETURN, returnAt, driven),
            RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700), blinking(8), listOf(off),
        )
        val code = ShareCode.encode(report)
        val summary = decoded(code)

        assertTrue(qrBytes(code) <= ShareCode.MAX_BYTES, "${qrBytes(code)}바이트")
        assertEquals("report-summary/2", summary.getValue("schema").jsonPrimitive.content)
        assertFalse("timeline" in summary, "타임라인은 빠진다")
        assertEquals(8, summary.getValue("timelineMore").jsonPrimitive.int, "건수는 전체를 센다")
        assertEquals(1, summary.getValue("gaps").jsonArray.size, "공백은 남는다")
        assertEquals(report.hash, summary.getValue("reportHash").jsonPrimitive.content, "원본 무결성 값을 함께 담는다")
    }

    @Test
    fun `앱이 자주 꺼진 대여도 QR을 만들 수 있게 공백을 줄인다`() {
        val many = List(40) { i -> RecordGap(t0.plusSeconds(10_000L * i), t0.plusSeconds(10_000L * i + 1_234)) }
        val report = RentalReport.build(record(SnapshotKind.PICKUP, pickupAt), record(SnapshotKind.RETURN, returnAt), RentalPolicy(), emptyList(), many)
        assertEquals(40, report.gaps.size)

        val code = ShareCode.encode(report)
        val summary = decoded(code)
        assertTrue(qrBytes(code) <= ShareCode.MAX_BYTES, "${qrBytes(code)}바이트")
        assertEquals(3, summary.getValue("gaps").jsonArray.size)
        assertEquals(37, summary.getValue("gapsMore").jsonPrimitive.int)
    }

    @Test
    fun `저장한 리포트를 규칙을 돌리지 않고 그대로 되살린다`() {
        val report = RentalReport.build(
            record(SnapshotKind.PICKUP, pickupAt), record(SnapshotKind.RETURN, returnAt),
            RentalPolicy(version = "demo/1", tankCapacityL = 47.0, fuelPricePerL = 1_700), blinking(7),
            listOf(RecordGap(t0.plusSeconds(100), t0.plusSeconds(200))),
        )
        val stored = Json.parseToJsonElement(Canonical.encode(JsonObject(report.toJson() + ("hash" to JsonPrimitive(report.hash))))).jsonObject

        val restored = RentalReport.fromJson(stored, report.pickup, report.returned)

        assertEquals(report.hash, restored.hash)
        assertEquals(report.toJson(), restored.toJson())
        assertEquals(report.timeline, restored.timeline)
        assertEquals(report.timelineMore, restored.timelineMore)
        assertEquals(report.gaps, restored.gaps)
        assertEquals(report.comparison, restored.comparison)
        assertEquals(ShareCode.encode(report), ShareCode.encode(restored), "닫은 뒤에도 같은 QR을 다시 만든다")
    }

    @Test
    fun `내용과 맞지 않거나 무결성 값이 없는 리포트는 받지 않는다`() {
        val report = RentalReport.build(record(SnapshotKind.PICKUP, pickupAt), record(SnapshotKind.RETURN, returnAt))
        val stored = JsonObject(report.toJson() + ("hash" to JsonPrimitive(report.hash)))

        assertFailsWith<IllegalArgumentException> { RentalReport.fromJson(JsonObject(stored + ("recordNo" to JsonPrimitive("x"))), report.pickup, report.returned) }
        assertFailsWith<IllegalArgumentException> { RentalReport.fromJson(JsonObject(stored - "hash"), report.pickup, report.returned) }
        assertFailsWith<IllegalArgumentException> { RentalReport.fromJson(stored, report.pickup, record(SnapshotKind.RETURN, returnAt.plusSeconds(1))) }
    }
}
