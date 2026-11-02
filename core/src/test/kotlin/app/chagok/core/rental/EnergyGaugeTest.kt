package app.chagok.core.rental

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 잔량 게이지(V-04, V-05). 리포트의 참고 정산과 같은 숫자가 나오고, 충전하면 함께 줄어들다가 눈금에 닿으면 사라진다. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnergyGaugeTest {
    private val policy = RentalPolicy(tankCapacityL = 47.0, fuelPricePerL = 1_700, batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)
    private val pickup = VehicleState(evSocPercent = 82.0, evCapacityKwh = 77.4)

    private fun battery(percent: Double) = ReturnCheck.energy(pickup, VehicleState(evSocPercent = percent), policy)!!

    @Test
    fun `전기차는 충전할 양과 참고 정산을 말한다`() {
        val gauge = battery(61.0)
        assertEquals(StateItem.EV_SOC, gauge.item)
        assertEquals(82, gauge.pickupPercent)
        assertEquals(61, gauge.nowPercent)
        assertFalse(gauge.reached)
        assertEquals("16.3kWh 충전하면 인수 때와 같습니다", gauge.refillText)
        assertEquals("참고 정산(업체 기준) 약 5,700원 · 청구 확정은 업체", gauge.settlementText)
        assertEquals("충전하고 다시 하기", gauge.retryLabel)
    }

    @Test
    fun `휘발유차는 주유할 양과 참고 정산을 말한다`() {
        val gauge = ReturnCheck.energy(VehicleState(fuelPercent = 82.0), VehicleState(fuelPercent = 61.0), policy)!!
        assertEquals(StateItem.FUEL, gauge.item)
        assertEquals("약 9.9L 주유하면 인수 때와 같습니다", gauge.refillText)
        assertEquals("참고 정산(업체 기준) 약 16,800원 · 청구 확정은 업체", gauge.settlementText)
        assertEquals("주유하고 다시 하기", gauge.retryLabel)
    }

    @Test
    fun `같은 잔량이면 리포트의 참고 정산과 같은 숫자다`() {
        listOf(61.0, 57.0, 79.0).forEach { now ->
            val note = settlementNote(82.0, now, 77.4, 350, "kWh", policy)!!
            val gauge = battery(now)
            assertTrue("${gauge.won!!.won()}" in note, "$note / ${gauge.won}")
            assertTrue(gauge.refillText!!.substringBefore(" 충전") in note, "$note / ${gauge.refillText}")
        }
    }

    @Test
    fun `충전하면 금액이 함께 줄고 눈금에 닿으면 금액이 사라진다`() {
        val gauge = battery(61.0)
        assertEquals(3_300, gauge.at(70.0).won)
        assertEquals("9.3kWh 충전하면 인수 때와 같습니다", gauge.at(70.0).refillText)

        // 리포트의 잔량 규칙과 같이 2%p까지는 줄어든 것으로 보지 않는다.
        listOf(80.0, 82.0, 90.0).forEach { now ->
            val full = gauge.at(now)
            assertTrue(full.reached, "$now")
            assertNull(full.refillText)
            assertNull(full.settlementText)
            assertNull(full.shortfall)
        }
        assertFalse(gauge.at(79.0).reached)
    }

    @Test
    fun `단가나 용량을 정하지 않았으면 금액을 지어내지 않는다`() {
        val noPrice = ReturnCheck.energy(pickup, VehicleState(evSocPercent = 61.0), RentalPolicy())!!
        assertEquals("16.3kWh 충전하면 인수 때와 같습니다", noPrice.refillText, "차가 준 용량은 쓴다")
        assertNull(noPrice.settlementText)

        val noCapacity = ReturnCheck.energy(VehicleState(fuelPercent = 82.0), VehicleState(fuelPercent = 61.0), RentalPolicy())!!
        assertEquals("인수 때보다 21%p 낮습니다", noCapacity.refillText)
        assertNull(noCapacity.settlementText)
    }

    @Test
    fun `잔량을 읽지 못했으면 게이지가 없고 둘 다 있으면 모자란 쪽을 보여 준다`() {
        assertNull(ReturnCheck.energy(pickup, VehicleState(), policy))
        assertNull(ReturnCheck.energy(VehicleState(), VehicleState(evSocPercent = 61.0), policy))

        val hybrid = ReturnCheck.energy(
            VehicleState(fuelPercent = 80.0, evSocPercent = 82.0),
            VehicleState(fuelPercent = 80.0, evSocPercent = 61.0),
            policy,
        )
        assertEquals(StateItem.EV_SOC, hybrid?.item)
    }

    @Test
    fun `도착 예상 비교도 같은 게이지를 쓴다`() {
        val plan = assertNotNull(ReturnTrip.plan(pickup, 56, policy))
        assertEquals(StateItem.EV_SOC, plan.gauge.item)
        assertEquals(56, plan.gauge.nowPercent)
        assertEquals("20.1kWh 충전하면 인수 때와 같습니다", plan.gauge.refillText)
        assertEquals("참고 정산(업체 기준) 약 7,000원 · 청구 확정은 업체", plan.gauge.settlementText)
        assertTrue(assertNotNull(ReturnTrip.plan(pickup, 81, policy)).gauge.reached)
    }

    @Test
    fun `반납 체크가 차를 읽을 때마다 게이지가 따라온다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(evSocPercent = 61.0, evCapacityKwh = 77.4))
        val seen = mutableListOf<CheckReading>()
        backgroundScope.launch { ReturnCheck.readings(vehicle, pickup, policy, pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()
        assertEquals(61, seen.last().energy?.nowPercent)
        assertEquals(listOf<CheckItem>(CheckItem.EnergyDrop(StateItem.EV_SOC, 82, 61)), seen.last().items)

        vehicle.update { copy(evSocPercent = 70.0) }
        advanceTimeBy(2_001)
        assertEquals(70, seen.last().energy?.nowPercent)

        vehicle.update { copy(evSocPercent = 80.0) }
        advanceTimeBy(2_001)
        assertEquals(true, seen.last().energy?.reached)
        assertEquals(emptyList(), seen.last().items, "허용 오차 안이면 잔량 줄도 없다")

        // 목록이 그대로여도 잔량이 바뀌면 다시 내보낸다.
        val before = seen.size
        vehicle.update { copy(evSocPercent = 82.0) }
        advanceTimeBy(2_001)
        assertEquals(before + 1, seen.size)
        assertEquals(82, seen.last().energy?.nowPercent)
    }

    @Test
    fun `게이지 문구에 금지어가 없다`() {
        val gauge = battery(61.0)
        listOf(
            gauge.refillText.orEmpty(), gauge.settlementText.orEmpty(), gauge.retryLabel, EnergyGauge.REACHED, ReturnTrip.ENOUGH,
            Rental.NOTICE_WHY, Rental.NOTICE_KEEP, Rental.NOTICE_DECLINE, Rental.IDLE_TITLE, Rental.PICKUP_TELLTALE_HINT,
            Rental.START_TITLE, Rental.START_TEXT, Rental.GAP_NOTE,
        ).forEach { assertEquals(emptyList(), Wording.violations(it), it) }
    }
}
