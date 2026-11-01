package app.chagok.core.rental

import app.chagok.core.rental.RentalEvent.AcceptNotice
import app.chagok.core.rental.RentalEvent.Close
import app.chagok.core.rental.RentalEvent.ConfirmReturn
import app.chagok.core.rental.RentalEvent.PickupFailed
import app.chagok.core.rental.RentalEvent.PickupSaved
import app.chagok.core.rental.RentalEvent.ReturnFailed
import app.chagok.core.rental.RentalEvent.ReturnSaved
import app.chagok.core.rental.RentalEvent.StartPickup
import app.chagok.core.rental.RentalEvent.StartReturnCheck
import app.chagok.core.rental.RentalState.IDLE
import app.chagok.core.rental.RentalState.IN_RENTAL
import app.chagok.core.rental.RentalState.NOTICE
import app.chagok.core.rental.RentalState.PICKUP
import app.chagok.core.rental.RentalState.RETURN
import app.chagok.core.rental.RentalState.RETURNED
import app.chagok.core.rental.RentalState.RETURN_CHECK
import app.chagok.core.rental.RentalState.VOIDED
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RentalStateTest {

    @Test
    fun `전이표대로 한 번의 대여를 끝까지 간다`() {
        val path = listOf(StartPickup, AcceptNotice, PickupSaved, StartReturnCheck, ConfirmReturn, ReturnSaved, Close)
        val states = path.runningFold<RentalEvent, RentalState?>(IDLE) { state, event -> state?.next(event) }
        assertEquals(listOf(IDLE, NOTICE, PICKUP, IN_RENTAL, RETURN_CHECK, RETURN, RETURNED, IDLE), states)
    }

    @Test
    fun `전이표에 없는 이동은 막는다`() {
        assertNull(IN_RENTAL.next(StartPickup))
        assertNull(IDLE.next(ConfirmReturn))
        assertNull(RETURNED.next(StartReturnCheck))
        assertNull(NOTICE.next(PickupSaved))
    }

    @Test
    fun `실패하면 앞 단계로 돌아가고, 무효 처리에는 사유가 있어야 한다`() {
        assertEquals(NOTICE, PICKUP.next(PickupFailed))
        assertEquals(RETURN_CHECK, RETURN.next(ReturnFailed))
        assertEquals(VOIDED, RETURN_CHECK.next(RentalEvent.Void("차량 교체")))
        assertNull(IN_RENTAL.next(RentalEvent.Void(" ")))
        assertEquals(IDLE, VOIDED.next(Close))
    }

    @Test
    fun `기록 번호는 차량-월일-순번이다`() {
        val day = LocalDate.of(2026, 11, 5)
        assertEquals("0007-1105-A1", RecordNumber.of("7", day, 0))
        assertEquals("0007-1105-B2", RecordNumber.of("0007", day, 10))
        assertEquals("1234-1105-Z9", RecordNumber.of("V-1234", day, RecordNumber.PER_DAY - 1))
        assertFailsWith<IllegalArgumentException> { RecordNumber.of("7", day, RecordNumber.PER_DAY) }
    }
}
