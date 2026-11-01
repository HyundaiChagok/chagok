package app.chagok.core.rental

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.driven
import app.chagok.core.fake.withDoor
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withLowTire
import app.chagok.core.fake.withWindow
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.TireState
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
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 차 없이 한 번의 대여를 처음부터 끝까지 돌린다(요구사항 TER-02). 공통 모듈을 이어 붙이는 순서의 예시이기도 하다. */
@OptIn(ExperimentalCoroutinesApi::class)
class RentalFlowTest {
    private val fourSeats = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.REAR_LEFT, Area.REAR_RIGHT)

    private val avante = VehicleState(
        fuelPercent = 82.0, odometerKm = 23_410.0, tripSinceRefillKm = 210.0,
        doorsOpen = fourSeats.associateWith { false }, windowsOpen = fourSeats.associateWith { false },
        headlightsOn = false, tailLampOn = false,
        seatsOccupied = mapOf(Area.FRONT_LEFT to true),
        engineOil = FluidLevel.NORMAL, brakeFluid = FluidLevel.NORMAL, washerFluid = FluidLevel.LOW,
        telltales = emptySet(),
        tires = fourSeats.associateWith { TireState(lowPressure = false) },
        airbagsDeployed = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to false),
        unavailable = mapOf(
            StateItem.EV_SOC to Unavailable.Unsupported,
            StateItem.EV_CHARGING to Unavailable.Unsupported,
            StateItem.CABIN_LIGHT to Unavailable.Unsupported,
        ),
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
            확인 필요 2 · 참고 2 · 변화 없음 8 · 확인 불가 1
            ${RentalReport.DISCLAIMER}

            확인 필요
            - 연료 82% → 61% (21%p 감소)
            - 참고 정산(업체 기준): 약 9.9L · 16,800원 · 청구 확정은 업체
            - 앞 왼쪽 타이어 공기압 낮음

            참고
            - 주행 312km
            - 워셔액 부족 (인수 때부터)

            변화 없음
            경고등, 에어백, 엔진오일, 브레이크액, 문, 창문, 전조등, 미등

            확인 불가
            - 실내등: 이 차에서 지원 안 함

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
}
