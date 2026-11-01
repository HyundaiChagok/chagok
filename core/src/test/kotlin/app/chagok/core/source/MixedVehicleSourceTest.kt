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
}
