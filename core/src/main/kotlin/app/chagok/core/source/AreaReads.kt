package app.chagok.core.source

import app.chagok.core.model.Area
import app.chagok.core.model.Unavailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 한 위치를 읽은 결과 */
sealed interface AreaReading<out T> {
    data class Value<T>(val value: T) : AreaReading<T>
    data class Missing(val reason: Unavailable) : AreaReading<Nothing>
}

/** 위치별 항목을 읽은 결과. 값이 없으면 [missing]에 이유가 있다. */
data class AreaValues<out T>(val values: Map<Area, T>, val missing: Unavailable? = null)

/**
 * 위치별 항목(문·창문·좌석·타이어·에어백)을 동시에 읽는다.
 *
 * 창문 하나만 못 읽었는데 나머지 3개로 '모두 닫힘'이 기록되면 안 되므로, 실패한 위치만 한 번 더 읽고 그래도 남으면
 * 항목 전체를 [Unavailable.Failed]로 돌려준다. 그 위치를 지원하지 않는 것은 실패가 아니라서 값에서 빼기만 한다.
 */
suspend fun <T> readAreas(areas: List<Area>, read: suspend (Area) -> AreaReading<T>): AreaValues<T> = coroutineScope {
    suspend fun readAll(targets: List<Area>) = targets.map { area -> async { area to read(area) } }.awaitAll().toMap()

    fun AreaReading<T>.failed() = (this as? AreaReading.Missing)?.reason as? Unavailable.Failed

    val first = readAll(areas)
    val readings = first + readAll(first.filterValues { it.failed() != null }.keys.toList())
    val failure = readings.values.firstNotNullOfOrNull { it.failed() }
    val values = readings.mapNotNull { (area, reading) -> (reading as? AreaReading.Value)?.let { area to it.value } }.toMap()
    when {
        failure != null -> AreaValues(emptyMap(), failure)
        values.isEmpty() -> AreaValues(emptyMap(), Unavailable.Unsupported)
        else -> AreaValues(values)
    }
}
