package app.chagok.core.rental

import app.chagok.core.inspect.Finding
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.InspectionRule
import app.chagok.core.inspect.Severity
import app.chagok.core.inspect.StandardRules
import app.chagok.core.inspect.Terms
import app.chagok.core.inspect.short
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 업체가 정하는 기준. 바꾸면 version을 올리고, 리포트에는 적용한 버전이 남는다. */
data class RentalPolicy(
    val version: String = "default/1",
    val energyTolerancePercent: Double = 2.0,
    /** 탱크 용량과 단가를 둘 다 정해야 참고 정산액을 보여 준다. */
    val tankCapacityL: Double? = null,
    val fuelPricePerL: Int? = null,
    val evNoticePercent: Double = 20.0,
)

/** 렌터카 프로필: 무엇을 기록하고, 어떤 규칙으로 비교하고, 리포트에 어떤 순서로 보여 주는지. */
object Rental {
    const val RULES_VERSION = "rental/1"

    /** 인수·반납 기록에 남기는 항목. 좌석 착석·문 잠금·주차 브레이크는 남기지 않는다(사람의 행동이거나 SDK에 없음). */
    val RECORDED: Set<StateItem> = setOf(
        StateItem.FUEL, StateItem.EV_SOC, StateItem.EV_CHARGING, StateItem.ODOMETER, StateItem.TRIP_SINCE_REFILL,
        StateItem.TELLTALES, StateItem.TIRES, StateItem.AIRBAGS,
        StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
        StateItem.DOORS, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

    /** 리포트 줄 순서. 주유 후 주행거리는 연료 줄의 근거로만 쓰고, 충전 스위치는 기록만 한다. */
    val REPORT_ORDER: List<StateItem> = listOf(
        StateItem.FUEL, StateItem.EV_SOC, StateItem.ODOMETER,
        StateItem.TELLTALES, StateItem.TIRES, StateItem.AIRBAGS,
        StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
        StateItem.DOORS, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

    /** 렌터카 규칙 묶음. 문 열림 횟수(DoorActivity)는 고객 행동이라 빼고, 좌석 감지(LeftBehind)는 반납 체크에서만 쓴다. */
    fun rules(policy: RentalPolicy = RentalPolicy()): List<InspectionRule> = listOf(
        StandardRules.Distance(),
        StandardRules.Energy(policy.energyTolerancePercent),
        FuelSettlement(policy),
        StandardRules.Refuel(terms = Terms.RENTAL),
        StandardRules.Telltales(Terms.RENTAL),
        StandardRules.Tires(),
        StandardRules.Airbags(Terms.RENTAL),
        StandardRules.Fluids(Terms.RENTAL),
        StandardRules.LeftOpen(),
    )

    /** 대여 중에 남기는 변화. 차의 상태만 남기고 문 열림·착석 같은 사람의 행동은 남기지 않는다. */
    fun recordsDuringRental(event: VehicleEvent): Boolean =
        event is VehicleEvent.TelltalesChanged || event is VehicleEvent.FluidChanged || event is VehicleEvent.AirbagChanged
}

/**
 * 연료 참고 정산. 업체가 탱크 용량과 단가를 정한 경우에만, 줄어든 연료를 업체 요율로 바꿔 보여 준다.
 * 게이지 %를 리터로 바꾸는 계산은 근사라서 "약"을 붙이고, 청구 확정은 업체가 한다.
 */
class FuelSettlement(private val policy: RentalPolicy) : InspectionRule {
    override fun check(ctx: InspectionContext): List<Finding> {
        val tank = policy.tankCapacityL ?: return emptyList()
        val price = policy.fuelPricePerL ?: return emptyList()
        val before = ctx.before.state.fuelPercent ?: return emptyList()
        val after = ctx.after.state.fuelPercent ?: return emptyList()
        if (after >= before || abs(after - before) <= policy.energyTolerancePercent) return emptyList()
        // 연료 줄("82% → 61%")과 같은 숫자가 나오도록 반올림한 %로 계산한다.
        val liters = (before.roundToInt() - after.roundToInt()) * tank / 100
        val won = (liters * price / 100).roundToInt() * 100
        return listOf(
            Finding(
                "fuel_settlement", Severity.INFO,
                "참고 정산(업체 기준): 약 ${liters.short()}L · ${String.format(Locale.ROOT, "%,d원", won)} · 청구 확정은 업체",
                StateItem.FUEL,
            ),
        )
    }
}
