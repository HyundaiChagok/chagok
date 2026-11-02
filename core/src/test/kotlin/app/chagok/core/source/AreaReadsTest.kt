package app.chagok.core.source

import app.chagok.core.model.Area
import app.chagok.core.model.Unavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AreaReadsTest {
    private val two = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT)
    private val timeout = Unavailable.Failed("TIMEOUT")

    @Test
    fun `실패한 자리만 한 번 더 읽어 채운다`() = runTest {
        val reads = mutableListOf<Area>()
        var failing = true
        val result = readAreas(two) { area ->
            reads += area
            if (area == Area.FRONT_RIGHT && failing) {
                failing = false
                AreaReading.Missing(timeout)
            } else {
                AreaReading.Value(false)
            }
        }
        assertEquals(AreaValues(two.associateWith { false }), result)
        assertEquals(listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.FRONT_RIGHT), reads)
    }

    @Test
    fun `그래도 못 읽으면 나머지 값으로 모두 닫힘이 되지 않게 항목 전체를 실패로 둔다`() = runTest {
        val result = readAreas(two) { area ->
            if (area == Area.FRONT_RIGHT) AreaReading.Missing(timeout) else AreaReading.Value(false)
        }
        assertEquals(AreaValues(emptyMap(), timeout), result)
    }

    @Test
    fun `그 자리를 지원하지 않는 것은 실패가 아니다`() = runTest {
        val reads = mutableListOf<Area>()
        val result = readAreas(two) { area ->
            reads += area
            if (area == Area.FRONT_RIGHT) AreaReading.Missing(Unavailable.Unsupported) else AreaReading.Value(true)
        }
        assertEquals(AreaValues(mapOf(Area.FRONT_LEFT to true)), result)
        assertEquals(two, reads, "지원하지 않는 자리는 다시 읽지 않는다")
    }

    @Test
    fun `모두 지원하지 않거나 자리가 없으면 지원 안 함이다`() = runTest {
        assertEquals(
            AreaValues(emptyMap(), Unavailable.Unsupported),
            readAreas<Boolean>(two) { AreaReading.Missing(Unavailable.Unsupported) },
        )
        assertEquals(AreaValues(emptyMap(), Unavailable.Unsupported), readAreas<Boolean>(emptyList()) { AreaReading.Value(true) })
    }
}
