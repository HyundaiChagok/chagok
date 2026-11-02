package app.chagok.core.rental

import app.chagok.core.inspect.display
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import java.time.Instant

/** 직원 화면 진단(V-07)의 항목 상태. 값이 없는 까닭을 [VehicleState.unavailable]과 같은 기준으로 나눈다. */
enum class ItemSupport(val label: String) {
    SUPPORTED("지원"),
    UNSUPPORTED("미지원"),
    FAILED("실패"),
}

/** 항목 하나의 진단. [value]는 지금 값이고, [reason]은 실패했을 때의 사유 코드, [lastCallback]은 SDK가 마지막으로 알려 온 시각이다. */
data class ItemDiagnosis(val item: StateItem, val support: ItemSupport, val value: String?, val reason: String?, val lastCallback: Instant?)

/** 한 번 읽은 진단. 화면에만 보이고 기록에 남지 않는다. */
data class Diagnosis(val rows: List<ItemDiagnosis>, val readAt: Instant) {
    fun count(support: ItemSupport): Int = rows.count { it.support == support }
}

object Diagnostics {
    /** 어댑터가 SDK에서 읽는 항목. 문 잠금과 주차 브레이크는 읽지 않는다. */
    val ITEMS: List<StateItem> = listOf(
        StateItem.EV_SOC, StateItem.FUEL, StateItem.EV_CHARGING, StateItem.EV_CAPACITY, StateItem.ODOMETER, StateItem.TRIP_SINCE_REFILL,
        StateItem.TELLTALES, StateItem.TIRES, StateItem.AIRBAGS, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
        StateItem.DOORS, StateItem.WINDOWS, StateItem.TRUNK, StateItem.CHARGE_PORT, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT, StateItem.SEATS,
    )

    fun of(state: VehicleState, lastCallbacks: Map<StateItem, Instant>, at: Instant): Diagnosis = Diagnosis(
        ITEMS.map { item ->
            val missing = state.missing(item)
            ItemDiagnosis(
                item = item,
                support = when (missing) {
                    null -> ItemSupport.SUPPORTED
                    Unavailable.Unsupported -> ItemSupport.UNSUPPORTED
                    is Unavailable.Failed -> ItemSupport.FAILED
                },
                value = state.display(item),
                reason = (missing as? Unavailable.Failed)?.reason,
                lastCallback = lastCallbacks[item],
            )
        },
        at,
    )
}
