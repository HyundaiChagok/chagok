package app.chagok.core.inspect

import app.chagok.core.inspect.Severity.WARNING
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 리포트 한 줄의 분류. 운영자가 먼저 볼 순서로 늘어놓았다. */
enum class Verdict(val label: String) {
    NEEDS_CHECK("확인 필요"),
    NOTE("참고"),
    UNCHANGED("변화 없음"),
    UNKNOWN("확인 불가"),
}

/** notes는 규칙이 남긴 문구이고, 확인 불가면 그 이유다. */
data class ComparisonRow(
    val item: StateItem,
    val before: String?,
    val after: String?,
    val verdict: Verdict,
    val notes: List<String>,
)

/** notApplicable은 휘발유차의 배터리처럼 이 차에 애초에 없는 항목이다. 확인 불가와 섞지 않는다. */
data class Comparison(val rows: List<ComparisonRow>, val notApplicable: List<StateItem>) {
    fun count(verdict: Verdict): Int = rows.count { it.verdict == verdict }
}

/**
 * [items]마다 한 줄을 만들어 분류 순서대로 정렬한다. 규칙이 아무 말도 하지 않은 항목도 "변화 없음"으로 남겨,
 * 확인했다는 사실 자체가 기록이 되게 한다.
 */
fun InspectionReport.compare(items: List<StateItem>): Comparison {
    val before = context.before.state
    val after = context.after.state
    val notApplicable = notApplicable(before, after).filter { it in items }
    val rows = items.filterNot { it in notApplicable }.map { item ->
        val notes = findings.filter { it.item == item }
        val missing = after.missing(item) ?: before.missing(item)?.takeIf { item !in END_STATE_ITEMS }
        val verdict = when {
            notes.any { it.severity == WARNING } -> Verdict.NEEDS_CHECK
            notes.isNotEmpty() -> Verdict.NOTE
            missing != null -> Verdict.UNKNOWN
            else -> Verdict.UNCHANGED
        }
        ComparisonRow(
            item = item,
            before = before.display(item),
            after = after.display(item),
            verdict = verdict,
            notes = if (notes.isNotEmpty()) notes.map { it.message } else listOfNotNull(missing?.describe()),
        )
    }
    return Comparison(rows.sortedBy { it.verdict }, notApplicable)
}

/** 리포트에 보일 값. 값이 없으면 null */
fun VehicleState.display(item: StateItem): String? = when (item) {
    StateItem.FUEL -> fuelPercent?.let { "${it.roundToInt()}%" }
    StateItem.EV_SOC -> evSocPercent?.let { "${it.roundToInt()}%" }
    StateItem.EV_CHARGING -> evCharging?.let { if (it) "충전 중" else "충전 안 함" }
    StateItem.ODOMETER -> odometerKm?.let { String.format(Locale.ROOT, "%,dkm", it.roundToLong()) }
    StateItem.TRIP_SINCE_REFILL -> tripSinceRefillKm?.let { "${it.short()}km" }
    StateItem.DOORS -> doorsOpen.summary("열림", "모두 닫힘")
    StateItem.DOOR_LOCKS -> doorsLocked.summary("잠김", "모두 풀림")
    StateItem.WINDOWS -> windowsOpen.summary("열림", "모두 닫힘")
    StateItem.HEADLIGHTS -> headlightsOn?.onOff()
    StateItem.TAIL_LAMP -> tailLampOn?.onOff()
    StateItem.CABIN_LIGHT -> cabinLightOn?.onOff()
    StateItem.PARKING_BRAKE -> parkingBrakeEngaged?.let { if (it) "걸림" else "풀림" }
    StateItem.SEATS -> seatsOccupied.summary("감지", "감지 없음")
    StateItem.ENGINE_OIL -> engineOil?.label
    StateItem.BRAKE_FLUID -> brakeFluid?.label
    StateItem.WASHER_FLUID -> washerFluid?.label
    StateItem.TELLTALES -> telltales?.let { set -> if (set.isEmpty()) "없음" else set.map { it.label }.sorted().joinToString() }
    StateItem.TIRES -> tires.takeIf { it.isNotEmpty() }?.let { map ->
        map.filterValues { it.lowPressure == true }.keys.sorted().takeIf { it.isNotEmpty() }
            ?.joinToString { "${it.side} 저압" } ?: "저압 없음"
    }
    StateItem.AIRBAGS -> airbagsDeployed.summary("전개", "전개 없음")
}

/** 확인 불가의 이유를 사람이 읽는 말로 */
fun Unavailable.describe(): String = when (this) {
    Unavailable.Unsupported -> "이 차에서 지원 안 함"
    is Unavailable.Failed -> "읽지 못함" + (FAILURE_LABELS[reason]?.let { " ($it)" } ?: "")
}

private val FAILURE_LABELS = mapOf(
    "TIMEOUT" to "시간 초과",
    "PERMISSION" to "권한 없음",
    "OUT_OF_RANGE" to "값이 범위 밖",
    "SDK_ERROR" to "차량 연결 오류",
)

/** 종료 시점만 보는 항목. 인수 때 값이 없어도 반납 때 값으로 판단한다. */
private val END_STATE_ITEMS = setOf(
    StateItem.DOORS, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT, StateItem.SEATS,
)

// 휘발유차의 배터리, 전기차의 연료처럼 두 시점 모두 "지원 안 함"이고 다른 동력원 값이 있으면 줄에서 뺀다.
private fun notApplicable(before: VehicleState, after: VehicleState): Set<StateItem> = buildSet {
    fun absent(item: StateItem) =
        before.missing(item) == Unavailable.Unsupported && after.missing(item) == Unavailable.Unsupported
    if (absent(StateItem.FUEL) && before.has(StateItem.EV_SOC)) addAll(listOf(StateItem.FUEL, StateItem.TRIP_SINCE_REFILL))
    if (absent(StateItem.EV_SOC) && before.has(StateItem.FUEL)) addAll(listOf(StateItem.EV_SOC, StateItem.EV_CHARGING))
}

private fun Boolean.onOff() = if (this) "켜짐" else "꺼짐"

private fun Map<Area, Boolean>.summary(on: String, none: String): String? {
    if (isEmpty()) return null
    val areas = filterValues { it }.keys.sorted()
    return if (areas.isEmpty()) none else areas.joinToString { it.label } + " " + on
}
