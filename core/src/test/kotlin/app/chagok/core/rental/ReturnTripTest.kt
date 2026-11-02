package app.chagok.core.rental

import app.chagok.core.inspect.InspectionContext
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReturnTripTest {
    private val policy = RentalPolicy(batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)
    private val pickup = VehicleState(evSocPercent = 82.0, evCapacityKwh = 77.4)

    @Test
    fun `도착 예상 배터리를 인수 때와 비교하고 낮으면 참고 정산을 함께 준다`() {
        val plan = assertNotNull(ReturnTrip.plan(pickup, 58, policy))
        assertEquals("도착하면 약 58%, 인수 때 82%", plan.summary)
        assertTrue(plan.lower)
        assertEquals("참고 정산(업체 기준): 약 18.6kWh · 6,500원 · 청구 확정은 업체", plan.settlement)
    }

    @Test
    fun `참고 정산은 같은 잔량이면 반납 리포트와 같은 금액이다`() {
        val state = VehicleState(evSocPercent = 58.0, evCapacityKwh = 77.4)
        val t0 = Instant.parse("2026-11-03T00:12:00Z")
        val reportNote = BatterySettlement(policy).check(
            InspectionContext(VehicleSnapshot(t0, null, pickup), VehicleSnapshot(t0.plusSeconds(60), null, state)),
        ).single().message
        assertEquals(reportNote, ReturnTrip.plan(pickup, 58, policy)?.settlement)
    }

    @Test
    fun `인수 때보다 높거나 허용 오차 안이면 결정할 것이 없다`() {
        val higher = assertNotNull(ReturnTrip.plan(pickup, 95, policy))
        assertFalse(higher.lower)
        assertNull(higher.settlement)
        assertEquals("도착하면 약 95%, 인수 때 82%", higher.summary)

        val sameAsReport = assertNotNull(ReturnTrip.plan(pickup, 80, policy))
        assertFalse(sameAsReport.lower, "리포트의 잔량 규칙과 같이 2%p까지는 줄어든 것으로 보지 않는다")
        assertNull(sameAsReport.settlement)

        assertTrue(assertNotNull(ReturnTrip.plan(pickup, 79, policy)).lower)
    }

    @Test
    fun `내비가 값을 주지 않으면 비교하지 않고 경로 안내만 한다`() {
        assertNull(ReturnTrip.plan(pickup, null, policy))
        assertNull(ReturnTrip.plan(pickup, 0, policy), "0은 값이 없는 것으로 본다")
        assertNull(ReturnTrip.plan(pickup, -1, policy))
        assertNull(ReturnTrip.plan(pickup, 101, policy))
    }

    @Test
    fun `인수 때 배터리를 읽지 못했으면 비교하지 않는다`() {
        assertNull(ReturnTrip.plan(VehicleState(fuelPercent = 82.0), 58, policy))
    }

    @Test
    fun `차가 용량을 주지 않으면 업체 기준으로 계산하고 단가가 없으면 금액을 말하지 않는다`() {
        val noCapacity = VehicleState(evSocPercent = 82.0)
        assertEquals(
            "참고 정산(업체 기준): 약 18.6kWh · 6,500원 · 청구 확정은 업체",
            ReturnTrip.plan(noCapacity, 58, policy)?.settlement,
        )
        val noPrice = assertNotNull(ReturnTrip.plan(pickup, 58, RentalPolicy()))
        assertTrue(noPrice.lower)
        assertNull(noPrice.settlement)
    }

    @Test
    fun `도착과 반납 안내 문구에 금지어가 없다`() {
        val plan = assertNotNull(ReturnTrip.plan(pickup, 58, policy))
        listOf(
            plan.summary, plan.settlement.orEmpty(), ReturnTrip.ARRIVED_TITLE, ReturnTrip.ARRIVED_TEXT,
            ReturnTrip.ESTIMATING, ReturnTrip.LOWER_HINT, ReturnTrip.ROUTE_FAILED,
        ).forEach { assertEquals(emptyList(), Wording.violations(it), it) }
    }
}
