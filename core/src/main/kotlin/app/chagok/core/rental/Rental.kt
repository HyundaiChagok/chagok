package app.chagok.core.rental

import app.chagok.core.inspect.Finding
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.InspectionRule
import app.chagok.core.inspect.Severity
import app.chagok.core.inspect.StandardRules
import app.chagok.core.inspect.Terms
import app.chagok.core.inspect.short
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    /** 차가 배터리 용량을 주지 않을 때 쓰는 값. 전기 단가를 정해야 참고 정산액을 보여 준다. */
    val batteryCapacityKwh: Double? = null,
    val electricityPricePerKwh: Int? = null,
    val evNoticePercent: Double = 20.0,
) {
    /** 시연용 가정인지. 리포트에는 버전만 남으므로 저장된 리포트도 버전으로 안다. */
    val demo: Boolean get() = version.startsWith(DEMO_VERSION_PREFIX)

    /** 참고 정산 금액의 근거. 시연 단가를 업체 기준이라고 부르면 안 된다. */
    val basis: String get() = if (demo) BASIS_DEMO else BASIS_OPERATOR

    companion object {
        const val DEMO_VERSION_PREFIX = "demo/"
        const val BASIS_DEMO = "시연 단가"
        const val BASIS_OPERATOR = "업체 기준"
    }
}

/** 렌터카 프로필: 무엇을 기록하고, 어떤 규칙으로 비교하고, 리포트에 어떤 순서로 보여 주는지. */
object Rental {
    /**
     * rental/2: 배터리 참고 정산, 트렁크·충전구, 공기압 경고등으로 판단하는 타이어, '(인수 때부터)' 타이어, 이 차에 없는 항목 숨김
     * rental/3: TPMS 경고등은 타이어 줄에서 한 번만 말한다. 주행 0km는 변화 없음이다. 잔량 줄의 '(21%p 감소)'를 뺐다.
     */
    const val RULES_VERSION = "rental/3"

    /** 기록을 차에 두는 날수. 지난 기록은 RecordStore.purgeExpired가 지운다. */
    const val RETENTION_DAYS = 180L

    /** 고지 화면(V-02)의 문구. [recordsDuringRental]이 실제로 남기는 것과 같아야 하고, 어느 차에서 읽어도 참이어야 한다. 차종마다 없는 항목이 있어 이름을 단정하지 않는다. */
    const val NOTICE_DURING_RENTAL = "대여 중에는 이 차가 알려 주는 경고등·소모품·안전장치 변화만 기록합니다. 위치와 운전 행동은 기록하지 않습니다."

    /** 고지 화면(V-02) '기록하는 것'의 제목에 붙이는 말 */
    const val NOTICE_ONLY_READABLE = "이 차가 알려 주는 항목만"

    /** 고지 화면(V-02): 왜 기록하는지와 거절하면 어떻게 되는지 */
    const val NOTICE_WHY = "출발 전 상태를 남겨 두면 반납 때 인수 상태와 비교할 수 있습니다. 같은 기록을 QR로 드립니다."
    const val NOTICE_KEEP = "기록은 인수·반납 상태 확인에만 쓰고 ${RETENTION_DAYS}일 보관한 뒤 지웁니다."
    const val NOTICE_DECLINE = "기록 없이 출발하면 반납 때 비교할 기록이 없습니다."

    /** 대기 화면(V-01)의 제목 */
    const val IDLE_TITLE = "출발 전 상태를 남기면 반납 때 근거가 됩니다"

    /** 인수 때 경고등이 켜져 있을 때(V-03). 대여 중에 말하는 것이 아니라 출발 전에 화면으로 알린다. */
    const val PICKUP_TELLTALE_HINT = "출발 전에 직원에게 보여 주세요"

    /** 차를 켰을 때 열린 대여가 없으면 띄우는 알림 */
    const val START_TITLE = "출발 전 상태를 남겨 둘까요?"
    const val START_TEXT = "눌러서 인수 기록을 시작합니다. 반납 때 근거가 됩니다."

    /** 리포트의 기록 공백(V-06). 변화 없음이 아니라 모르는 구간이라고 사실대로 적는다. */
    const val GAP_NOTE = "이 구간은 차가 꺼져 있었거나 기록하지 못했습니다."

    /** 1분이 안 되는 구간은 시작과 끝이 같은 분으로 보여 한 시각에 이 말을 붙인다. */
    const val UNDER_A_MINUTE = "1분 미만"

    /** 시각 두 개를 나란히 적으면 같은 분으로 보이는 구간인지 */
    fun underAMinute(from: Instant, to: Instant): Boolean =
        java.time.Duration.between(from, to).let { !it.isNegative && it < java.time.Duration.ofMinutes(1) }

    /**
     * 리포트의 대여 중 기록이 비었을 때. 이 차가 실제로 읽은 항목만 이름을 댄다. 전기차에 없는 소모품·에어백을 '변화 없음'으로 읽히게 하면 안 된다.
     * 주행과 잔량은 이 기록에 들지 않으므로([recordsDuringRental]) '변화 없음'이라고 뭉뚱그리지 않는다.
     */
    fun timelineEmpty(pickup: VehicleState): String = listOfNotNull(
        watchedNames(pickup).takeIf { it.isNotEmpty() }?.let { "$it 변화가 기록되지 않았습니다." },
        unreadNames(pickup).takeIf { it.isNotEmpty() }?.let { "${it.josa("은", "는")} 인수 때 읽지 못해 변화를 알 수 없습니다." },
    ).joinToString(" ").ifEmpty { "이 차에서 대여 중에 읽는 항목이 없습니다." }

    /** 대여 중 화면(V-04)의 '지금까지 변화 없음' 줄. [timelineEmpty]와 같은 기준이다. */
    fun timelineQuiet(pickup: VehicleState?): String {
        val read = pickup?.let(::watchedNames).orEmpty()
        val unread = pickup?.let(::unreadNames).orEmpty()
        return listOfNotNull(
            read.takeIf { it.isNotEmpty() }?.let { "지금까지 $it 변화가 없습니다" },
            unread.takeIf { it.isNotEmpty() }?.let { "$it 읽지 못함" },
        ).joinToString("  ·  ").ifEmpty { "이 차에서 대여 중에 읽는 항목이 없습니다" }
    }

    private fun watchedNames(state: VehicleState) = names(WATCHED_ORDER.filter(state::has))

    private fun unreadNames(state: VehicleState) = names(WATCHED_ORDER.filter { state.missing(it) is Unavailable.Failed })

    // 소모품 셋을 모두 읽을 때만 '소모품'이라고 묶는다. 일부만 읽으면 그 이름을 댄다.
    private fun names(items: List<StateItem>): String {
        val allFluids = items.containsAll(FLUIDS)
        return items.mapNotNull { item ->
            when {
                !allFluids || item !in FLUIDS -> item.label
                item == FLUIDS.first() -> FLUIDS_NAME
                else -> null
            }
        }.joinToString("·")
    }

    /** 대여 중 기록 서비스의 알림 */
    const val RECORDING_TITLE = "차곡 대여 중 기록"
    const val RECORDING_TEXT = "이 차가 알려 주는 경고등·소모품·안전장치 변화만 기록합니다. 위치와 운전 행동은 기록하지 않습니다."

    /**
     * 인수·반납 기록에 남기는 항목. 좌석 착석·주차 브레이크는 남기지 않는다(사람의 행동이거나 SDK에 없음).
     * 문 잠금도 남기지 않는다. 읽을 수는 있지만 기록은 고객이 차 안에 앉아 있을 때 찍으므로 차의 상태라기보다 그 순간 고객의 행동이다.
     */
    val RECORDED: Set<StateItem> = setOf(
        StateItem.FUEL, StateItem.EV_SOC, StateItem.EV_CHARGING, StateItem.EV_CAPACITY, StateItem.ODOMETER, StateItem.TRIP_SINCE_REFILL,
        StateItem.TELLTALES, StateItem.TIRES, StateItem.AIRBAGS,
        StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
        StateItem.DOORS, StateItem.TRUNK, StateItem.CHARGE_PORT, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

    /** 리포트 줄 순서. 주유 후 주행거리는 연료 줄의 근거로, 배터리 용량은 참고 정산의 근거로만 쓰고, 충전 스위치는 기록만 한다. */
    val REPORT_ORDER: List<StateItem> = listOf(
        StateItem.FUEL, StateItem.EV_SOC, StateItem.ODOMETER,
        StateItem.TELLTALES, StateItem.TIRES, StateItem.AIRBAGS,
        StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID,
        StateItem.DOORS, StateItem.TRUNK, StateItem.CHARGE_PORT, StateItem.WINDOWS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

    /** 대여 중에 구독하는 항목. [recordsDuringRental]을 통과하는 것만이다. 나머지 값은 대여 중에 듣지도 않는다. */
    val WATCHED_DURING_RENTAL: Set<StateItem> = setOf(
        StateItem.TELLTALES, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.AIRBAGS,
    )

    private val WATCHED_ORDER = listOf(StateItem.TELLTALES, StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.AIRBAGS)
    private val FLUIDS = setOf(StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID)
    private const val FLUIDS_NAME = "소모품"

    /** 렌터카 규칙 묶음. 문 열림 횟수(DoorActivity)는 고객 행동이라 빼고, 좌석 감지(LeftBehind)는 반납 체크에서만 쓴다. */
    fun rules(policy: RentalPolicy = RentalPolicy()): List<InspectionRule> = listOf(
        StandardRules.Distance(),
        StandardRules.Energy(policy.energyTolerancePercent),
        FuelSettlement(policy),
        BatterySettlement(policy),
        StandardRules.Refuel(terms = Terms.RENTAL),
        // TPMS는 타이어 줄이 말한다(Tires). 두 줄로 세면 '확인 필요' 건수가 어긋난다.
        StandardRules.Telltales(Terms.RENTAL, spokenElsewhere = setOf(Telltale.TPMS)),
        StandardRules.Tires(terms = Terms.RENTAL),
        StandardRules.Airbags(Terms.RENTAL),
        StandardRules.Fluids(Terms.RENTAL),
        StandardRules.LeftOpen(),
    )

    /** 대여 중에 남기는 변화. 차의 상태만 남기고 문 열림·착석 같은 사람의 행동은 남기지 않는다. */
    fun recordsDuringRental(event: VehicleEvent): Boolean =
        event is VehicleEvent.TelltalesChanged || event is VehicleEvent.FluidChanged || event is VehicleEvent.AirbagChanged

    /** 대여 중 이벤트를 시간순 문구로 바꾼다. 시각은 초까지만 남긴다. */
    fun timeline(pickup: VehicleState, events: List<LoggedEvent>): List<TimelineEntry> {
        val fold = TimelineFold(pickup)
        return events.sortedBy { it.at }.flatMap { logged ->
            fold.apply(logged.event).map { TimelineEntry(logged.at.truncatedTo(ChronoUnit.SECONDS), it) }
        }
    }

    /**
     * 구독이 끊긴 구간([VehicleEvent.SOURCE_ERROR]~[VehicleEvent.SOURCE_RESUMED])을 [recorded]와 합쳐 시간순으로 돌려준다.
     * 끊긴 채 끝났으면 [end]까지를 공백으로 본다. 겹치는 구간은 하나로 합친다(공백을 적은 뒤 생존 시각을 쓰기 전에 앱이 다시 꺼지면 겹친다).
     * 시계가 거꾸로 간 구간처럼 길이가 없는 것은 뺀다.
     */
    fun gaps(events: List<LoggedEvent>, recorded: List<RecordGap>, end: Instant): List<RecordGap> {
        var cutAt: Instant? = null
        val cuts = buildList {
            for (logged in events) {
                when ((logged.event as? VehicleEvent.Note)?.type) {
                    VehicleEvent.SOURCE_ERROR -> if (cutAt == null) cutAt = logged.at
                    VehicleEvent.SOURCE_RESUMED -> {
                        cutAt?.let { add(RecordGap(it, logged.at)) }
                        cutAt = null
                    }
                }
            }
            cutAt?.let { add(RecordGap(it, end)) }
        }
        return (recorded + cuts)
            .map { RecordGap(it.from.truncatedTo(ChronoUnit.SECONDS), it.to.truncatedTo(ChronoUnit.SECONDS)) }
            .filter { it.from < it.to }
            .sortedBy { it.from }
            .fold(emptyList()) { merged, gap ->
                val last = merged.lastOrNull()
                if (last != null && gap.from <= last.to) merged.dropLast(1) + RecordGap(last.from, maxOf(last.to, gap.to)) else merged + gap
            }
    }
}

/**
 * 연료 참고 정산. 업체가 탱크 용량과 단가를 정한 경우에만, 줄어든 연료를 업체 요율로 바꿔 보여 준다.
 * 게이지 %를 리터로 바꾸는 계산은 근사라서 "약"을 붙이고, 청구 확정은 업체가 한다.
 */
class FuelSettlement(private val policy: RentalPolicy) : InspectionRule {
    override fun check(ctx: InspectionContext): List<Finding> {
        val tank = policy.tankCapacityL ?: return emptyList()
        val price = policy.fuelPricePerL ?: return emptyList()
        return settlement("fuel_settlement", StateItem.FUEL, ctx.before.state.fuelPercent, ctx.after.state.fuelPercent, tank, price, "L", policy)
    }
}

/**
 * 배터리 참고 정산. 연료와 같은 틀이다. 줄어든 배터리를 kWh와 업체 요율로 바꿔 보여 주고, 청구 확정은 업체가 한다.
 * 용량은 인수 기록에 남은 차의 값을 먼저 쓰고, 차가 주지 않았으면 업체 기준을 쓴다. 단가는 업체만 정한다.
 */
class BatterySettlement(private val policy: RentalPolicy) : InspectionRule {
    override fun check(ctx: InspectionContext): List<Finding> {
        val before = ctx.before.state
        val after = ctx.after.state
        val capacity = before.evCapacityKwh ?: after.evCapacityKwh ?: policy.batteryCapacityKwh ?: return emptyList()
        val price = policy.electricityPricePerKwh ?: return emptyList()
        return settlement("battery_settlement", StateItem.EV_SOC, before.evSocPercent, after.evSocPercent, capacity, price, "kWh", policy)
    }
}

private fun settlement(
    ruleId: String,
    item: StateItem,
    before: Double?,
    after: Double?,
    capacity: Double,
    pricePerUnit: Int,
    unit: String,
    policy: RentalPolicy,
): List<Finding> {
    if (before == null || after == null) return emptyList()
    val note = settlementNote(before, after, capacity, pricePerUnit, unit, policy) ?: return emptyList()
    return listOf(Finding(ruleId, Severity.INFO, note, item))
}

/** 참고 정산 문구. 허용 오차보다 많이 줄지 않았으면 null이다. 리포트와 반납 전 안내가 같은 숫자를 보여 준다. */
internal fun settlementNote(before: Double, after: Double, capacity: Double, pricePerUnit: Int, unit: String, policy: RentalPolicy): String? {
    if (after >= before || abs(after - before) <= policy.energyTolerancePercent) return null
    val amount = settlementAmount(before, after, capacity)
    return "참고 정산(${policy.basis}): 약 ${amount.short()}$unit · ${settlementWon(amount, pricePerUnit).won()} · 청구 확정은 업체"
}

// 잔량 줄("82% → 61%")과 같은 숫자가 나오도록 반올림한 %로 계산한다.
internal fun settlementAmount(before: Double, after: Double, capacity: Double): Double =
    (before.roundToInt() - after.roundToInt()) * capacity / 100

internal fun settlementWon(amount: Double, pricePerUnit: Int): Int = (amount * pricePerUnit / 100).roundToInt() * 100

internal fun Int.won(): String = String.format(Locale.ROOT, "%,d원", this)
