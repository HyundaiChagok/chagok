package app.chagok.core.rental

import app.chagok.core.inspect.short
import app.chagok.core.model.StateItem
import kotlin.math.roundToInt

/**
 * 잔량 게이지의 값(V-04 '반납하러 가기', V-05 반납 체크). 인수 때 눈금과 지금 잔량, 눈금까지 모자란 양과 그 참고 정산을 함께 준다.
 * 양과 금액은 리포트의 참고 정산과 같은 계산이라 같은 잔량이면 같은 숫자가 나온다.
 * [reached]는 허용 오차 안으로 찼는지다. 리포트의 잔량 규칙과 같은 기준이라, 여기서 찼다고 하면 리포트에는 '변화 없음'으로 남는다.
 * 화면은 값이 바뀌는 동안 [at]으로 사이 값을 만들어 막대와 숫자를 함께 움직인다.
 */
data class EnergyGauge(
    val item: StateItem,
    val pickup: Double,
    val now: Double,
    val tolerancePercent: Double,
    val capacity: Double? = null,
    val pricePerUnit: Int? = null,
    /** 단가의 근거([RentalPolicy.basis]). 시연 단가를 업체 기준이라고 부르지 않는다. */
    val basis: String = RentalPolicy.BASIS_OPERATOR,
) {
    val pickupPercent: Int get() = pickup.roundToInt()
    val nowPercent: Int get() = now.roundToInt()
    val reached: Boolean get() = pickup - now <= tolerancePercent

    private val isFuel: Boolean get() = item == StateItem.FUEL
    private val unit: String get() = if (isFuel) "L" else "kWh"
    private val refill: String get() = if (isFuel) "주유" else "충전"

    /** 눈금까지 모자란 양. 찼거나 용량을 모르면 null이다. */
    val shortfall: Double? get() = capacity?.takeIf { !reached }?.let { settlementAmount(pickup, now, it) }

    /** 모자란 양의 참고 금액. 단가를 정하지 않았으면 null이다. */
    val won: Int? get() = shortfall?.let { amount -> pricePerUnit?.let { settlementWon(amount, it) } }

    /** 예: 16.3kWh 충전하면 인수 때와 같습니다. 찼으면 null이다. 게이지 %를 리터로 바꾸는 계산은 근사라서 연료에는 '약'을 붙인다. */
    val refillText: String?
        get() = when {
            reached -> null
            else -> shortfall?.let { "${if (isFuel) "약 " else ""}${it.short()}$unit ${refill}하면 인수 때와 같습니다" }
                ?: "인수 때보다 ${pickupPercent - nowPercent}%p 낮습니다"
        }

    /** 예: 참고 정산(시연 단가) 약 5,700원 · 청구 확정은 업체. 금액을 모르면 null이다. */
    val settlementText: String? get() = won?.let { "참고 정산($basis) 약 ${it.won()} · 청구 확정은 업체" }

    /** 반납 체크를 미루는 버튼의 이름. 모자랄 때만 쓴다. */
    val retryLabel: String get() = "${refill}하고 다시 하기"

    fun at(percent: Double): EnergyGauge = copy(now = percent)

    companion object {
        const val REACHED = "인수 때만큼 찼습니다"
    }
}
