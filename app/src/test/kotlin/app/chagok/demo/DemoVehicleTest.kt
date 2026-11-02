package app.chagok.demo

import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoVehicleTest {
    @Test
    fun `대여 중 변화는 두 번 눌러도 한 번만 적용된다`() {
        val demo = DemoVehicle()
        demo.driveDuringRental()
        val once = demo.fake.current
        demo.driveDuringRental()

        assertEquals(once, demo.fake.current)
        assertEquals(DemoVehicle.START.odometerKm!! + 312.0, demo.fake.current.odometerKm)
        assertEquals(61.0, demo.fake.current.fuelPercent)
    }

    @Test
    fun `휘발유 이야기에는 배터리와 충전구가 없다`() {
        val start = DemoVehicle.START
        listOf(StateItem.EV_SOC, StateItem.EV_CAPACITY, StateItem.CHARGE_PORT).forEach {
            assertEquals(Unavailable.Unsupported, start.missing(it), it.name)
            assertTrue(it in DemoVehicle.SCRIPTED, "SDK의 전기차 값이 섞이면 안 된다: $it")
        }
        assertEquals(false, start.trunkOpen)
    }

    @Test
    fun `처음 상태로 돌리면 다시 적용할 수 있다`() {
        val demo = DemoVehicle()
        demo.driveDuringRental()
        demo.reset()
        assertEquals(DemoVehicle.START, demo.fake.current)

        demo.driveDuringRental()
        assertEquals(DemoVehicle.START.odometerKm!! + 312.0, demo.fake.current.odometerKm)
    }
}
