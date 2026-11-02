package app.chagok.core.rental

import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleState
import kotlin.math.roundToInt

/** 반납 지점. 내비에 넘기는 값이고 기록에는 남지 않는다. */
data class ReturnPoint(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String,
    val poiSubId: String,
)

/**
 * 반납하러 가기 전에 보여 주는 비교(기능 정의서 F-RET-05, F-EV-03). 충전하고 반납할지 고객이 미리 결정하게 한다.
 * [lower]는 도착 예상 배터리가 허용 오차보다 많이 낮은지다. 리포트의 잔량 규칙과 같은 기준이다. 낮으면 [settlement]와 근처 충전소를 함께 보여 준다.
 */
data class ReturnPlan(val pickupPercent: Int, val arrivalPercent: Int, val lower: Boolean, val settlement: String?, val gauge: EnergyGauge) {
    val summary: String get() = "도착하면 약 $arrivalPercent%, 인수 때 $pickupPercent%"
}

object ReturnTrip {
    const val ARRIVED_TITLE = "반납 지점에 도착했습니다"
    const val ARRIVED_TEXT = "눌러서 반납 체크를 시작합니다"
    const val ESTIMATING = "도착 예상 배터리를 확인하는 중입니다"
    const val LOWER_HINT = "충전하고 반납할지 미리 정할 수 있습니다."

    /** 도착 예상 배터리가 인수 때만큼일 때 게이지에 보이는 말 */
    const val ENOUGH = "도착해도 인수 때만큼 남습니다"
    const val ROUTE_FAILED = "반납 지점 경로를 찾지 못했습니다. 내비게이션에서 직접 검색해 주세요."

    /**
     * 도착 예상 배터리를 인수 기록과 비교한다. 내비가 값을 주지 않았거나(0 이하나 100 초과는 값이 아닌 것으로 본다)
     * 인수 때 배터리를 읽지 못했으면(휘발유차 등) 비교할 것이 없어 null이고, 부르는 쪽은 경로 안내만 한다.
     */
    fun plan(pickup: VehicleState, estimatedPercent: Int?, policy: RentalPolicy): ReturnPlan? {
        val before = pickup.evSocPercent ?: return null
        val arrival = estimatedPercent?.takeIf { it in 1..100 } ?: return null
        val capacity = pickup.evCapacityKwh ?: policy.batteryCapacityKwh
        val price = policy.electricityPricePerKwh
        val note = if (capacity != null && price != null) settlementNote(before, arrival.toDouble(), capacity, price, "kWh", policy) else null
        val gauge = EnergyGauge(StateItem.EV_SOC, before, arrival.toDouble(), policy.energyTolerancePercent, capacity, price, policy.basis)
        return ReturnPlan(before.roundToInt(), arrival, !gauge.reached, note, gauge)
    }
}
