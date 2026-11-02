package app.chagok.core.source

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.withDoor
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MixedVehicleSourceTest {
    private val sdk = FakeVehicle(
        VehicleState(
            doorsOpen = mapOf(Area.FRONT_LEFT to false),
            unavailable = mapOf(StateItem.FUEL to Unavailable.Failed("EMULATOR")),
        ),
    )
    private val script = FakeVehicle(VehicleState(fuelPercent = 82.0, engineOil = FluidLevel.NORMAL, doorsOpen = mapOf(Area.FRONT_LEFT to true)))
    private val mixed = MixedVehicleSource(sdk, script, setOf(StateItem.FUEL, StateItem.ENGINE_OIL))

    @Test
    fun `정한 항목만 대본 값을 쓰고 나머지는 SDK 값을 쓴다`() = runTest {
        val state = mixed.state()
        assertEquals(82.0, state.fuelPercent)
        assertEquals(FluidLevel.NORMAL, state.engineOil)
        assertEquals(mapOf(Area.FRONT_LEFT to false), state.doorsOpen)
        assertEquals(emptyMap(), state.unavailable)
    }

    @Test
    fun `변화도 항목별로 맞는 쪽에서만 받는다`() = runTest {
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch { mixed.changes().collect { seen += it } }
        runCurrent()

        sdk.update { withDoor(Area.FRONT_LEFT, true).copy(engineOil = FluidLevel.LOW) }
        script.update { withDoor(Area.FRONT_LEFT, false).copy(engineOil = FluidLevel.LOW) }
        runCurrent()

        assertEquals(
            listOf(VehicleEvent.DoorChanged(Area.FRONT_LEFT, true), VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)),
            seen,
        )
    }

    @Test
    fun `일부 항목만 구독하면 그 항목의 변화만 온다`() = runTest {
        val seen = mutableListOf<VehicleEvent>()
        backgroundScope.launch { mixed.changes(setOf(StateItem.ENGINE_OIL)).collect { seen += it } }
        runCurrent()

        sdk.update { withDoor(Area.FRONT_LEFT, true).copy(engineOil = FluidLevel.LOW) }
        script.update { withDoor(Area.FRONT_LEFT, false).copy(engineOil = FluidLevel.LOW) }
        runCurrent()

        assertEquals(listOf<VehicleEvent>(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)), seen)
    }

    @Test
    fun `차대번호는 SDK 쪽에서 읽는다`() = runTest {
        val withVin = object : VehicleSource by sdk {
            override suspend fun vin(): String = "KMHC851HFNU123456"
        }
        assertEquals("KMHC851HFNU123456", MixedVehicleSource(withVin, script, emptySet()).vin())
    }
}
