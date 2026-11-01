package app.chagok.core.inspect

import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.session.CompletedSession
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

enum class Severity { INFO, WARNING }

/** item은 리포트에서 이 결과가 들어갈 줄이다. 여러 항목에 걸치면 null */
data class Finding(val ruleId: String, val severity: Severity, val message: String, val item: StateItem? = null)

/**
 * 규칙이 보는 재료.
 *
 * events가 null이면 세션 기록이 없다는 뜻이고, 빈 리스트면 기록은 했는데 변화가 없었다는 뜻이다.
 */
data class InspectionContext(
    val before: VehicleSnapshot,
    val after: VehicleSnapshot,
    val events: List<LoggedEvent>? = null,
) {
    val duration: Duration
        get() = java.time.Duration.between(before.capturedAt, after.capturedAt).toKotlinDuration()

    /** 주행거리계 차이. 값을 모르거나 거꾸로 줄었으면 null */
    val distanceKm: Double?
        get() {
            val b = before.state.odometerKm ?: return null
            val a = after.state.odometerKm ?: return null
            return (a - b).takeIf { it >= 0 }
        }
}

fun interface InspectionRule {
    fun check(ctx: InspectionContext): List<Finding>
}

/** 아이디어마다 필요한 규칙만 골라 넣는다. 예: 렌터카는 Energy를 엄격하게, 차 맡김은 Distance와 DoorActivity 위주. */
class Inspector(private val rules: List<InspectionRule> = StandardRules.all()) {
    fun inspect(ctx: InspectionContext): InspectionReport =
        InspectionReport(ctx, rules.flatMap { it.check(ctx) })

    fun inspect(session: CompletedSession): InspectionReport =
        inspect(InspectionContext(session.start, session.end, session.events))
}

data class InspectionReport(
    val context: InspectionContext,
    val findings: List<Finding>,
)
