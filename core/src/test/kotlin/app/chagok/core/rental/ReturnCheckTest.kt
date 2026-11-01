package app.chagok.core.rental

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withWindow
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReturnCheckTest {

    @Test
    fun `고칠 수 있는 것부터 좌석, 잔량, 경고등 순으로 늘어놓는다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val pickup = VehicleState(fuelPercent = 82.0, telltales = emptySet())
        val now = VehicleState(
            fuelPercent = 61.0,
            telltales = setOf(engine),
            windowsOpen = mapOf(Area.FRONT_RIGHT to true, Area.FRONT_LEFT to false),
            doorsOpen = mapOf(Area.FRONT_LEFT to false),
            headlightsOn = false,
            cabinLightOn = true,
            seatsOccupied = mapOf(Area.FRONT_LEFT to true, Area.REAR_LEFT to true),
        )
        assertEquals(
            listOf(
                CheckItem.WindowOpen(Area.FRONT_RIGHT),
                CheckItem.LightOn(StateItem.CABIN_LIGHT),
                CheckItem.SeatDetected(Area.REAR_LEFT),
                CheckItem.EnergyDrop(StateItem.FUEL, 82, 61),
                CheckItem.NewTelltales(setOf(engine)),
            ),
            ReturnCheck.items(pickup, now),
        )
    }

    @Test
    fun `잔량이 허용치 안이거나 인수 때부터 켜진 경고등은 알리지 않는다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val items = ReturnCheck.items(
            VehicleState(fuelPercent = 82.0, telltales = setOf(engine)),
            VehicleState(fuelPercent = 80.5, telltales = setOf(engine)),
        )
        assertEquals(emptyList(), items)
    }

    @Test
    fun `고객이 고치면 목록에서 바로 빠진다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(windowsOpen = mapOf(Area.FRONT_RIGHT to true), cabinLightOn = true))
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = null).collect { seen += it } }
        runCurrent()

        vehicle.update { withWindow(Area.FRONT_RIGHT, false) }
        runCurrent()
        vehicle.update { withLight(StateItem.CABIN_LIGHT, false) }
        runCurrent()

        assertEquals(
            listOf(
                listOf(CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.LightOn(StateItem.CABIN_LIGHT)),
                listOf(CheckItem.LightOn(StateItem.CABIN_LIGHT)),
                emptyList(),
            ),
            seen,
        )
    }

    @Test
    fun `이벤트가 없는 값은 주기적으로 다시 읽는다`() = runTest {
        var state = VehicleState(headlightsOn = true)
        val silent = object : VehicleSource {
            override suspend fun state() = state
            override fun changes(): Flow<VehicleEvent> = emptyFlow()
        }
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(silent, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        state = VehicleState(headlightsOn = false)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf(listOf(CheckItem.LightOn(StateItem.HEADLIGHTS)), emptyList()), seen)
    }
}
