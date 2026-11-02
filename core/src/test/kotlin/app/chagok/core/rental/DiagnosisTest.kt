package app.chagok.core.rental

import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosisTest {
    private val at = Instant.parse("2026-11-03T03:40:00Z")

    @Test
    fun `항목마다 지원과 미지원과 실패를 나누고 값과 콜백 시각을 함께 준다`() {
        val state = VehicleState(
            evSocPercent = 82.0,
            unavailable = mapOf(StateItem.ENGINE_OIL to Unavailable.Unsupported, StateItem.WINDOWS to Unavailable.Failed("TIMEOUT")),
        )
        val heard = at.minusSeconds(5)
        val diagnosis = Diagnostics.of(state, mapOf(StateItem.EV_SOC to heard), at)
        fun row(item: StateItem) = diagnosis.rows.single { it.item == item }

        assertEquals(ItemDiagnosis(StateItem.EV_SOC, ItemSupport.SUPPORTED, "82%", null, heard), row(StateItem.EV_SOC))
        assertEquals(ItemSupport.UNSUPPORTED, row(StateItem.ENGINE_OIL).support)
        assertNull(row(StateItem.ENGINE_OIL).value)
        assertEquals(ItemSupport.FAILED, row(StateItem.WINDOWS).support)
        assertEquals("TIMEOUT", row(StateItem.WINDOWS).reason)
        assertNull(row(StateItem.WINDOWS).lastCallback)
        assertEquals(at, diagnosis.readAt)
        assertEquals(1, diagnosis.count(ItemSupport.SUPPORTED))
        assertEquals(1, diagnosis.count(ItemSupport.UNSUPPORTED))
    }

    @Test
    fun `이유를 적지 않은 빈 값은 지원으로 보이지 않고 실패로 남는다`() {
        val diagnosis = Diagnostics.of(VehicleState(), emptyMap(), at)
        assertEquals(Diagnostics.ITEMS.size, diagnosis.count(ItemSupport.FAILED))
        assertEquals(setOf(VehicleState.NOT_READ), diagnosis.rows.map { it.reason }.toSet())
    }

    @Test
    fun `읽지 않는 문 잠금과 주차 브레이크는 진단 항목에 없다`() {
        assertEquals(false, StateItem.DOOR_LOCKS in Diagnostics.ITEMS)
        assertEquals(false, StateItem.PARKING_BRAKE in Diagnostics.ITEMS)
    }
}
