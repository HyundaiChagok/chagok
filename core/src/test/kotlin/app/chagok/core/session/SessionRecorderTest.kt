package app.chagok.core.session

import app.chagok.core.fake.FakeLocation
import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.driven
import app.chagok.core.fake.withDoor
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.GeoPoint
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecorderTest {
    private val cityHall = GeoPoint(37.5663, 126.9779)
    private val gwanghwamun = GeoPoint(37.5759, 126.9768)

    private fun TestScope.virtualNow(): () -> Instant = { Instant.ofEpochMilli(testScheduler.currentTime) }

    @Test
    fun `남기지 않기로 한 이벤트는 기록하지 않는다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(engineOil = FluidLevel.NORMAL))
        val session = SessionRecorder(vehicle, now = virtualNow()).start(backgroundScope) { it !is VehicleEvent.DoorChanged }
        runCurrent()

        vehicle.update { withDoor(Area.FRONT_LEFT, true).copy(engineOil = FluidLevel.LOW) }
        runCurrent()

        assertEquals(
            listOf(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)),
            session.finish().events.map { it.event },
        )
    }

    @Test
    fun `방금 찍은 시작·종료 상태를 넘기면 차를 다시 읽지 않는다`() = runTest {
        val fake = FakeVehicle(VehicleState(fuelPercent = 60.0))
        var reads = 0
        val counting = object : VehicleSource {
            override suspend fun state(): VehicleState = fake.state().also { reads++ }
            override fun changes(): Flow<VehicleEvent> = fake.changes()
        }
        val initial = VehicleSnapshot(at(0), null, fake.state())
        val end = VehicleSnapshot(at(60), null, fake.state())

        val done = SessionRecorder(counting, now = virtualNow()).start(backgroundScope, initial).finish(end)

        assertEquals(0, reads)
        assertEquals(initial, done.start)
        assertEquals(end, done.end)
    }

    private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)

    @Test
    fun `세션 동안의 변화를 시각·위치와 함께 기록한다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(odometerKm = 1_000.0, fuelPercent = 60.0))
        val location = FakeLocation(cityHall)
        val session = SessionRecorder(vehicle, location, virtualNow()).start(backgroundScope)

        advanceTimeBy(60_000)
        vehicle.update { withDoor(Area.FRONT_LEFT, true) }
        runCurrent()

        advanceTimeBy(60_000)
        location.moveTo(gwanghwamun)
        vehicle.update { withDoor(Area.FRONT_LEFT, false).driven(km = 4.2, fuelUsedPercent = 1.0) }
        runCurrent()

        session.note("feature", "통풍 시트")
        val done = session.finish()

        assertEquals(at(0), done.start.capturedAt)
        assertEquals(cityHall, done.start.location)
        assertEquals(
            listOf(
                LoggedEvent(at(60), cityHall, VehicleEvent.DoorChanged(Area.FRONT_LEFT, true)),
                LoggedEvent(at(120), gwanghwamun, VehicleEvent.Moved(gwanghwamun)),
                LoggedEvent(at(120), gwanghwamun, VehicleEvent.DoorChanged(Area.FRONT_LEFT, false)),
                LoggedEvent(at(120), gwanghwamun, VehicleEvent.Note("feature", "통풍 시트")),
            ),
            done.events,
        )
        assertEquals(1_004.2, done.end.state.odometerKm!!, 1e-9)
        assertEquals(gwanghwamun, done.end.location)
    }

    @Test
    fun `종료 후의 변화는 기록하지 않는다`() = runTest {
        val vehicle = FakeVehicle()
        val session = SessionRecorder(vehicle, now = virtualNow()).start(backgroundScope)
        session.finish()

        vehicle.update { withDoor(Area.FRONT_RIGHT, true) }
        runCurrent()

        assertEquals(emptyList(), session.events.value)
    }

    @Test
    fun `구독이 끊기면 기록에 남기고 세션은 이어 간다`() = runTest {
        val broken = object : VehicleSource {
            override suspend fun state() = VehicleState()
            override fun changes(): Flow<VehicleEvent> = flow {
                emit(VehicleEvent.ParkingBrakeChanged(true))
                throw IllegalStateException("door listener failed")
            }
        }
        val location = FakeLocation(cityHall)
        val session = SessionRecorder(broken, location, virtualNow()).start(backgroundScope)
        runCurrent()

        location.moveTo(gwanghwamun)
        runCurrent()

        assertEquals(
            listOf(
                VehicleEvent.ParkingBrakeChanged(true),
                VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, "door listener failed"),
                VehicleEvent.Moved(gwanghwamun),
            ),
            session.finish().events.map { it.event },
        )
    }

    @Test
    fun `이어 가는 세션은 저장해 둔 이벤트에 덧붙이고 새 이벤트만 알린다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(engineOil = FluidLevel.NORMAL))
        val before = LoggedEvent(at(1), null, VehicleEvent.FluidChanged(StateItem.WASHER_FLUID, FluidLevel.LOW))
        val told = mutableListOf<LoggedEvent>()
        val session = SessionRecorder(vehicle, now = virtualNow()).start(backgroundScope, restored = listOf(before), onEvent = { told += it })
        runCurrent()

        vehicle.update { copy(engineOil = FluidLevel.LOW) }
        runCurrent()

        val new = LoggedEvent(at(0), null, VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW))
        assertEquals(listOf(new), told)
        assertEquals(listOf(before, new), session.events.value)
    }

    @Test
    fun `구독이 끊기면 기다렸다가 다시 구독하고 그 시각을 남긴다`() = runTest {
        val fake = FakeVehicle(VehicleState(engineOil = FluidLevel.NORMAL))
        var attempts = 0
        val flaky = object : VehicleSource {
            override suspend fun state() = VehicleState()
            override fun changes(): Flow<VehicleEvent> = flow {
                if (attempts++ == 0) throw IllegalStateException("차 서비스 끊김")
                emitAll(fake.changes())
            }
        }
        val session = SessionRecorder(flaky, now = virtualNow()).start(backgroundScope, resubscribeAfter = 1.minutes)
        runCurrent()

        fake.update { copy(engineOil = FluidLevel.LOW) } // 끊긴 동안의 변화는 보지 못한다.
        advanceTimeBy(1.minutes)
        runCurrent()
        fake.update { copy(engineOil = FluidLevel.NORMAL) }
        runCurrent()

        assertEquals(
            listOf(
                LoggedEvent(at(0), null, VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, "차 서비스 끊김")),
                LoggedEvent(at(60), null, VehicleEvent.Note(VehicleEvent.SOURCE_RESUMED)),
                LoggedEvent(at(60), null, VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.NORMAL)),
            ),
            session.events.value,
        )
    }
}
