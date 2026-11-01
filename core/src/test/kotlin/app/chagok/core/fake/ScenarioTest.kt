package app.chagok.core.fake

import app.chagok.core.inspect.Inspector
import app.chagok.core.inspect.StandardRules
import app.chagok.core.inspect.toText
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.GeoPoint
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.session.SessionRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Test
    fun `대본을 가상 시간에 맞춰 차례로 재생한다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(odometerKm = 100.0, fuelPercent = 50.0))
        val script = scenario {
            update("출발") { copy(parkingBrakeEngaged = false) }
            wait(10.minutes)
            update("4.2km 주행") { driven(km = 4.2, fuelUsedPercent = 2.0) }
        }
        val labels = mutableListOf<String>()
        launch { script.play(vehicle) { if (it is Scenario.Step.Update) labels += it.label } }

        runCurrent()
        assertEquals(listOf("출발"), labels)
        assertEquals(100.0, vehicle.current.odometerKm)

        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(listOf("출발", "4.2km 주행"), labels)
        assertEquals(48.0, vehicle.current.fuelPercent)
    }

    @Test
    fun `상태가 바뀌면 해당 이벤트를 내보낸다`() {
        val before = VehicleState(parkingBrakeEngaged = true, seatsOccupied = mapOf(Area.FRONT_LEFT to true))
        val after = before
            .copy(parkingBrakeEngaged = false, telltales = setOf(Telltale("TPMS", "타이어 공기압")))
            .withSeat(Area.FRONT_RIGHT, true)
            .withDoor(Area.REAR_LEFT, true)

        assertEquals(
            listOf(
                VehicleEvent.DoorChanged(Area.REAR_LEFT, true),
                VehicleEvent.ParkingBrakeChanged(false),
                VehicleEvent.SeatOccupancyChanged(Area.FRONT_RIGHT, true),
                VehicleEvent.TelltalesChanged(setOf(Telltale("TPMS", "타이어 공기압"))),
            ),
            eventsBetween(before, after),
        )
    }

    /** 공통 모듈을 이어 붙이는 방법의 예시이기도 하다: 대본 재생 → 세션 기록 → 점검 → 리포트 */
    @Test
    fun `차 맡김 시나리오를 처음부터 끝까지 돌린다`() = runTest {
        val shop = GeoPoint(37.5172, 127.0473)
        val vehicle = FakeVehicle(
            VehicleState(
                odometerKm = 23_410.0, tripSinceRefillKm = 210.0, fuelPercent = 64.0,
                engineOil = FluidLevel.LOW, washerFluid = FluidLevel.NORMAL, telltales = emptySet(),
            ),
        )
        val location = FakeLocation(GeoPoint(37.5110, 127.0590))
        val recorder = SessionRecorder(vehicle, location) { Instant.parse("2026-11-01T01:00:00Z").plusMillis(testScheduler.currentTime) }

        val session = recorder.start(backgroundScope)
        scenario {
            wait(20.minutes)
            update("정비소로 이동") { driven(km = 2.1, fuelUsedPercent = 0.5) }
            moveTo(shop)
            update("정비사 탑승") { withDoor(Area.FRONT_LEFT, true) }
            update("문 닫힘") { withDoor(Area.FRONT_LEFT, false) }
            wait(40.minutes)
            update("엔진오일 보충") { copy(engineOil = FluidLevel.NORMAL) }
            update("돌아옴") { driven(km = 2.1, fuelUsedPercent = 0.5) }
        }.play(vehicle, location)
        runCurrent()

        val report = Inspector(StandardRules.all()).inspect(session.finish())

        assertEquals(
            """
            점검 리포트
            2026-11-01 10:00 → 2026-11-01 11:00 (1시간)

            참고
            - 주행 4.2km
            - 엔진오일 부족 → 정상
            - 문 열림 1회 (운전석 1)
            """.trimIndent(),
            report.toText(),
        )
    }
}
