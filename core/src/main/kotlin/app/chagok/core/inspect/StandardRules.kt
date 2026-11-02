package app.chagok.core.inspect

import app.chagok.core.inspect.Severity.INFO
import app.chagok.core.inspect.Severity.WARNING
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.TireState
import app.chagok.core.model.VehicleEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** 시작과 진행을 부르는 말. 렌터카 리포트에서는 "인수 때부터", "대여 중"으로 쓴다. */
data class Terms(val start: String, val during: String) {
    companion object {
        val GENERIC = Terms(start = "시작", during = "사용")
        val RENTAL = Terms(start = "인수", during = "대여")
    }
}

/** 시작·종료 스냅샷 비교로 판단하는 기본 규칙. 모르는 값(null)은 판단하지 않는다. */
object StandardRules {

    fun all(terms: Terms = Terms.GENERIC): List<InspectionRule> = listOf(
        Distance(), Energy(), Refuel(terms = terms), Fluids(terms), Telltales(terms), Tires(terms = terms), Airbags(terms),
        LeftOpen(), LeftBehind(), DoorActivity(),
    )

    /** 주행거리. warnAboveKm를 넘으면 경고로 올린다(차 맡김에서 "무단 운행" 기준). 값이 줄었으면 '변화 없음'으로 두지 않고 그대로 알린다. */
    class Distance(private val warnAboveKm: Double? = null) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val before = ctx.before.state.odometerKm
            val after = ctx.after.state.odometerKm
            if (before != null && after != null && after < before) {
                return listOf(Finding("distance", WARNING, "주행거리 줄어듦: ${before.short()} → ${after.short()}km", StateItem.ODOMETER))
            }
            val km = ctx.distanceKm ?: return emptyList()
            // 화면에 '주행 0km'로 보일 거리는 변화가 아니다.
            if (km.short() == "0") return emptyList()
            val severity = if (warnAboveKm != null && km > warnAboveKm) WARNING else INFO
            return listOf(Finding("distance", severity, "주행 ${km.short()}km", StateItem.ODOMETER))
        }
    }

    /** 연료·배터리 잔량. 센서 흔들림을 감안해 tolerancePercent 이내 변화는 무시한다. 배터리가 늘었으면 충전한 것이다. */
    class Energy(private val tolerancePercent: Double = 2.0) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val b = ctx.before.state
            val a = ctx.after.state
            return listOfNotNull(
                level(StateItem.FUEL, b.fuelPercent, a.fuelPercent),
                level(StateItem.EV_SOC, b.evSocPercent, a.evSocPercent),
            )
        }

        private fun level(item: StateItem, before: Double?, after: Double?): Finding? {
            if (before == null || after == null) return null
            if (abs(after - before) <= tolerancePercent) return null
            val b = before.roundToInt()
            val a = after.roundToInt()
            return Finding("energy", if (a < b) WARNING else INFO, "${item.label} $b% → $a%", item)
        }
    }

    /** 주유 후 주행거리가 중간에 초기화됐으면 사용 중에 주유한 것으로 본다. 연료 줄의 근거라서 item은 FUEL이다. */
    class Refuel(private val toleranceKm: Double = 1.0, private val terms: Terms = Terms.GENERIC) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val b = ctx.before.state.tripSinceRefillKm ?: return emptyList()
            val a = ctx.after.state.tripSinceRefillKm ?: return emptyList()
            // 주유하지 않았다면 a ≈ b + 주행거리
            val expected = b + (ctx.distanceKm ?: 0.0)
            if (a + toleranceKm >= expected) return emptyList()
            return listOf(
                Finding("refuel", INFO, "${terms.during} 중 주유 기록 있음 (주유 후 ${a.short()}km 주행)", StateItem.FUEL),
            )
        }
    }

    class Fluids(private val terms: Terms = Terms.GENERIC) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val b = ctx.before.state
            val a = ctx.after.state
            return listOfNotNull(
                fluid(StateItem.ENGINE_OIL, b.engineOil, a.engineOil),
                fluid(StateItem.BRAKE_FLUID, b.brakeFluid, a.brakeFluid),
                fluid(StateItem.WASHER_FLUID, b.washerFluid, a.washerFluid),
            )
        }

        private fun fluid(item: StateItem, before: FluidLevel?, after: FluidLevel?): Finding? {
            val name = item.label
            return when {
                after == null -> null
                before == after ->
                    if (after == FluidLevel.LOW) Finding("fluids", INFO, "$name 부족 (${terms.start} 때부터)", item) else null
                before == null ->
                    if (after == FluidLevel.LOW) Finding("fluids", INFO, "$name 부족 (${terms.start} 때 확인 못 함)", item) else null
                after == FluidLevel.LOW -> Finding("fluids", WARNING, "$name ${before.label} → ${after.label}", item)
                else -> Finding("fluids", INFO, "$name ${before.label} → ${after.label}", item)
            }
        }
    }

    /**
     * 새로 켜진 경고등만 경고로 올리고, 처음부터 켜져 있던 것은 참고로 둔다(분쟁 대비).
     * 시작 때 읽지 못했으면 새로 켜졌는지 알 수 없으므로 고객에게 불리하게 경고로 올리지 않는다.
     * [spokenElsewhere]는 다른 규칙이 말하는 경고등의 코드다. 같은 경고등을 두 줄로 세지 않으려고 뺀다.
     */
    class Telltales(private val terms: Terms = Terms.GENERIC, private val spokenElsewhere: Set<String> = emptySet()) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val after = ctx.after.state.telltales?.filterNot { it.code in spokenElsewhere }?.toSet() ?: return emptyList()
            val before = ctx.before.state.telltales?.filterNot { it.code in spokenElsewhere }?.toSet()
            fun finding(severity: Severity, message: String) = Finding("telltales", severity, message, StateItem.TELLTALES)
            if (before == null) return after.map { finding(INFO, "경고등 켜짐 (${terms.start} 때 확인 못 함): ${it.label}") }
            return (after - before).map { finding(WARNING, "경고등 켜짐: ${it.label}") } +
                (after intersect before).map { finding(INFO, "경고등 켜짐 (${terms.start} 때부터): ${it.label}") } +
                (before - after).map { finding(INFO, "경고등 꺼짐: ${it.label}") }
        }
    }

    /**
     * 저압이 새로 나타났거나 공기압 수치가 크게 떨어진 바퀴. 인수 때부터 낮았던 바퀴는 참고로 두고 '(인수 때부터)'를 붙인다(고객을 보호하는 사실이다).
     * 시작 때 저압 여부를 읽지 못한 바퀴는 새로 나타났다고 말하지 않는다.
     *
     * 수치와 경고등이 어긋나면 낮은 쪽으로 본다. 어느 바퀴도 낮지 않은데 공기압 경고등이 켜졌으면 경고등을 말한다.
     * 수치를 주지 않는 차는 경고등이 유일한 근거다. 어느 바퀴인지는 말할 수 없다.
     */
    class Tires(private val dropWarnKpa: Double = 20.0, private val terms: Terms = Terms.GENERIC) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val wheels = ctx.after.state.tires.entries.sortedBy { it.key }.mapNotNull { (area, after) -> wheel(area, ctx.before.state.tires[area], after) }
            return wheels + listOfNotNull(lamp(ctx))
        }

        private fun wheel(area: Area, before: TireState?, after: TireState): Finding? {
            val bp = before?.pressureKpa
            val ap = after.pressureKpa
            return when {
                after.lowPressure == true && before?.lowPressure == null ->
                    Finding("tires", INFO, "${area.side} 타이어 공기압 낮음 (${terms.start} 때 확인 못 함)", StateItem.TIRES)
                after.lowPressure == true && before?.lowPressure == true ->
                    Finding("tires", INFO, "${area.side} 타이어 공기압 낮음 (${terms.start} 때부터)", StateItem.TIRES)
                after.lowPressure == true ->
                    Finding("tires", WARNING, "${area.side} 타이어 공기압 낮음", StateItem.TIRES)
                bp != null && ap != null && bp - ap >= dropWarnKpa ->
                    Finding("tires", WARNING, "${area.side} 타이어 공기압 ${bp.roundToInt()} → ${ap.roundToInt()}kPa", StateItem.TIRES)
                before?.lowPressure == true && after.lowPressure == false ->
                    Finding("tires", INFO, "${area.side} 타이어 공기압 회복", StateItem.TIRES)
                else -> null
            }
        }

        private fun lamp(ctx: InspectionContext): Finding? {
            val after = ctx.after.state
            if (after.tires.values.any { it.lowPressure == true }) return null
            val was = ctx.before.state.tirePressureLamp
            fun finding(severity: Severity, message: String) = Finding("tires", severity, message, StateItem.TIRES)
            return when {
                after.tirePressureLamp == true -> when (was) {
                    null -> finding(INFO, "타이어 공기압 경고등 켜짐 (${terms.start} 때 확인 못 함)")
                    true -> finding(INFO, "타이어 공기압 경고등 켜짐 (${terms.start} 때부터)")
                    false -> finding(WARNING, "타이어 공기압 경고등 켜짐")
                }
                after.tirePressureLamp == false && was == true -> finding(INFO, "타이어 공기압 경고등 꺼짐")
                else -> null
            }
        }
    }

    /** 에어백 전개. 무엇이 언제 바뀌었는지만 적고 원인은 적지 않는다. 시작 때 읽지 못한 자리는 새로 전개됐다고 말하지 않는다. */
    class Airbags(private val terms: Terms = Terms.GENERIC) : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val before = ctx.before.state.airbagsDeployed
            val deployed = ctx.after.state.airbagsDeployed.filterValues { it }.keys.sorted()
            return listOfNotNull(
                deployed.filter { before[it] == false }.labels()?.let { Finding("airbags", WARNING, "에어백 전개 기록: $it", StateItem.AIRBAGS) },
                deployed.filter { before[it] == true }.labels()?.let { Finding("airbags", INFO, "에어백 전개 기록 (${terms.start} 때부터): $it", StateItem.AIRBAGS) },
                deployed.filter { it !in before }.labels()?.let { Finding("airbags", INFO, "에어백 전개 기록 (${terms.start} 때 확인 못 함): $it", StateItem.AIRBAGS) },
            )
        }
    }

    /** 종료 시점에 열려 있거나 켜져 있는 것. */
    class LeftOpen : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val s = ctx.after.state
            fun on(value: Boolean?, item: StateItem) =
                if (value == true) Finding("left_open", WARNING, "${item.label} 켜짐", item) else null
            return listOfNotNull(
                s.doorsOpen.trueAreas()?.let { Finding("left_open", WARNING, "문 열림: $it", StateItem.DOORS) },
                s.windowsOpen.trueAreas()?.let { Finding("left_open", WARNING, "창문 열림: $it", StateItem.WINDOWS) },
                if (s.trunkOpen == true) Finding("left_open", WARNING, "트렁크 열림", StateItem.TRUNK) else null,
                if (s.chargePortOpen == true) Finding("left_open", WARNING, "충전구 열림", StateItem.CHARGE_PORT) else null,
                on(s.headlightsOn, StateItem.HEADLIGHTS),
                on(s.tailLampOn, StateItem.TAIL_LAMP),
                on(s.cabinLightOn, StateItem.CABIN_LIGHT),
            )
        }
    }

    /**
     * 종료 시점에 운전석 말고 다른 좌석에 무언가 감지되면 두고 내린 물건을 확인하게 한다.
     * 조수석·뒷좌석 감지 지원 여부는 차마다 달라서, 어댑터가 지원하는 좌석만 채운다.
     */
    class LeftBehind : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val seats = ctx.after.state.seatsOccupied.filterKeys { it != Area.FRONT_LEFT }.trueAreas()
                ?: return emptyList()
            return listOf(Finding("left_behind", WARNING, "좌석 감지: $seats (두고 내린 물건 확인)", StateItem.SEATS))
        }
    }

    /** 세션 동안 문이 열린 횟수. 기록이 없으면(events == null) 판단하지 않는다. */
    class DoorActivity : InspectionRule {
        override fun check(ctx: InspectionContext): List<Finding> {
            val events = ctx.events ?: return emptyList()
            val openings = events.mapNotNull { (it.event as? VehicleEvent.DoorChanged)?.takeIf { e -> e.open }?.area }
            if (openings.isEmpty()) return listOf(Finding("door_activity", INFO, "문 열림 없음", StateItem.DOORS))
            val byArea = openings.groupingBy { it }.eachCount().entries.sortedBy { it.key }
                .joinToString { "${it.key.label} ${it.value}" }
            return listOf(Finding("door_activity", INFO, "문 열림 ${openings.size}회 ($byArea)", StateItem.DOORS))
        }
    }
}

private fun Map<Area, Boolean>.trueAreas(): String? =
    filterValues { it }.keys.sorted().takeIf { it.isNotEmpty() }?.joinToString { it.label }

private fun List<Area>.labels(): String? = takeIf { it.isNotEmpty() }?.joinToString { it.label }

/** 소수 첫째 자리까지, ".0"은 뗀다. 4.2 → "4.2", 37.0 → "37" */
internal fun Double.short(): String = String.format(java.util.Locale.ROOT, "%.1f", this).removeSuffix(".0")
