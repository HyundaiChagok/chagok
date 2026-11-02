package app.chagok.ui

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreensTimeTest {
    private val at = Instant.parse("2026-11-03T03:40:50Z")

    @Test
    fun `1분이 안 되는 구간은 시각 하나만 쓴다`() {
        assertEquals("11.03 12:40 (1분 미만)", span(at, at.plusSeconds(20)))
        assertEquals("11.03 12:40 → 12:41", span(at, at.plusSeconds(60)))
        assertEquals("11.03 12:40 → 11.04 12:40", span(at, at.plusSeconds(86_400)))
    }

    @Test
    fun `QR 시간은 마지막 입력부터 재고 화면을 다시 만들어도 처음부터 세지 않는다`() {
        assertEquals(180_000L, idleLeftMillis(lastInput = 1_000, now = 1_000))
        assertEquals(116_000L, idleLeftMillis(lastInput = 1_000, now = 65_000), "1분쯤 뒤에 화면을 다시 만들어도 남은 시간만 기다린다")
        assertEquals(0L, idleLeftMillis(lastInput = 1_000, now = 500_000))
    }

    @Test
    fun `저장한 시각이 지금보다 뒤면(재부팅) 3분을 다 준다`() {
        assertEquals(180_000L, idleLeftMillis(lastInput = 500_000, now = 1_000))
    }
}
