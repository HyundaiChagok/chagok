package app.chagok.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleStateTest {

    @Test
    fun `값이 없는 이유를 지원 안 함과 읽지 못함으로 나눈다`() {
        val state = VehicleState(
            fuelPercent = 70.0,
            unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported, StateItem.BRAKE_FLUID to Unavailable.Failed("TIMEOUT")),
        )
        assertEquals(null, state.missing(StateItem.FUEL))
        assertEquals(Unavailable.Unsupported, state.missing(StateItem.CABIN_LIGHT))
        assertEquals(Unavailable.Failed("TIMEOUT"), state.missing(StateItem.BRAKE_FLUID))
        assertEquals(Unavailable.Failed(VehicleState.NOT_READ), state.missing(StateItem.ENGINE_OIL))
    }

    @Test
    fun `기록용 상태는 정한 항목만 남기고 빈 값마다 이유를 붙인다`() {
        val state = VehicleState(
            fuelPercent = 70.0,
            seatsOccupied = mapOf(Area.FRONT_RIGHT to true),
            unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported, StateItem.FUEL to Unavailable.Failed("TIMEOUT")),
        )
        val record = state.forRecord(setOf(StateItem.FUEL, StateItem.CABIN_LIGHT, StateItem.ENGINE_OIL))

        assertEquals(70.0, record.fuelPercent)
        assertEquals(emptyMap(), record.seatsOccupied)
        assertEquals(
            mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported, StateItem.ENGINE_OIL to Unavailable.Failed(VehicleState.NOT_READ)),
            record.unavailable,
        )
    }
}
