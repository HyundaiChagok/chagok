package app.chagok.core.rental

import app.chagok.core.model.VehicleState
import kotlin.math.roundToInt

/**
 * 음성 안내 문구(기능 정의서 10절 T-01~T-11). 한 번에 [MAX_SECONDS] 안에 끝나게 만들고,
 * 대여 중에는 운전과 관련된 [lowBattery]만 쓴다.
 */
object Voice {
    const val MAX_SECONDS = 15.0

    // 한국어 TTS 기본 속도를 초당 5음절로 잡은 보수적인 값
    private const val SYLLABLES_PER_SECOND = 5.0

    /** T-01 */
    fun notice(): String = "차량 상태를 기록할까요? 위치와 개인정보는 기록하지 않습니다."

    /** T-02, 인수 때 켜진 경고등이 있으면 T-03을 합친다. */
    fun pickupDone(state: VehicleState): String {
        val energy = state.fuelPercent?.let { "연료 ${it.roundToInt()}퍼센트" }
            ?: state.evSocPercent?.let { "배터리 ${it.roundToInt()}퍼센트" }
        val telltales = state.telltales?.let { if (it.isEmpty()) "켜진 경고등은 없습니다" else "켜져 있는 경고등 ${it.size}개를 함께 남겼습니다" }
        val middle = when {
            energy != null && telltales != null -> "$energy, $telltales. "
            energy != null -> "${energy}입니다. "
            telltales != null -> "$telltales. "
            else -> ""
        }
        return "인수 기록을 남겼습니다. ${middle}화면의 QR로 같은 기록을 받으세요."
    }

    /**
     * T-04·T-06·T-07·T-08. 고칠 것 → 좌석 → 잔량 → 경고등 순으로 말하다가 15초를 넘길 것 같으면 멈추고,
     * 나머지는 "외 N가지는 화면을 확인해 주세요"로 넘긴다.
     */
    fun returnCheck(items: List<CheckItem>): String {
        val fixes = items.filter { it.fixable }
        val seats = items.filterIsInstance<CheckItem.SeatDetected>()
        val count = fixes.size + seats.size
        val ending = if (count == 0) "반납 기록을 남길까요?" else null
        val candidates = buildList {
            if (fixes.isNotEmpty()) add(openClause(fixes.take(3)) to fixes.size.coerceAtMost(3))
            if (seats.isNotEmpty()) add(seatSentence(seats) to seats.size)
            items.filterIsInstance<CheckItem.EnergyDrop>().forEach { add(energySentence(it) to 1) }
            items.filterIsInstance<CheckItem.NewTelltales>().forEach { add("새로 켜진 경고등 ${it.telltales.size}개를 기록에 남깁니다." to 1) }
        }
        val parts = mutableListOf(if (count == 0) "확인할 것이 없습니다." else "반납 전에 ${count}가지를 확인해 주세요.")
        var skipped = (fixes.size - 3).coerceAtLeast(0)
        val reserve = estimatedSeconds(more(9))
        for ((sentence, covers) in candidates) {
            val trial = (parts + sentence + listOfNotNull(ending)).joinToString(" ")
            if (estimatedSeconds(trial) + reserve <= MAX_SECONDS) parts += sentence else skipped += covers
        }
        if (skipped > 0) parts += more(skipped)
        ending?.let { parts += it }
        return parts.joinToString(" ")
    }

    /** T-05. 알려 주기만 하는 항목(잔량·경고등)은 해결 안내가 없어 null */
    fun resolved(done: CheckItem, remaining: List<CheckItem>): String? {
        val first = when (done) {
            is CheckItem.WindowOpen, is CheckItem.DoorOpen -> "${done.subject.josa("이", "가")} 닫혔습니다."
            is CheckItem.LightOn -> "${done.subject.josa("이", "가")} 꺼졌습니다."
            is CheckItem.SeatDetected -> "${done.subject.josa("이", "가")} 비었습니다."
            else -> return null
        }
        val left = remaining.filter { it.actionable }
        val next = when (left.size) {
            0 -> "모두 확인됐습니다. 반납 기록을 남길까요?"
            1 -> "${left.single().subject}만 남았습니다."
            else -> "${left.size}가지 남았습니다."
        }
        return "$first $next"
    }

    /**
     * T-12. 반납 체크 도중 새로 열리거나 켜진 것. 고객이 화면을 보지 않고 내리려 할 때를 위한 안내다.
     * 잔량·경고등은 기록에만 남기고 말하지 않는다(SAF-02).
     */
    fun appeared(item: CheckItem, now: List<CheckItem>): String? {
        val first = when (item) {
            is CheckItem.WindowOpen, is CheckItem.DoorOpen -> "${item.subject.josa("이", "가")} 열렸습니다."
            is CheckItem.LightOn -> "${item.subject.josa("이", "가")} 켜졌습니다."
            is CheckItem.SeatDetected -> "${item.area.label}에 무언가 감지됩니다."
            else -> return null
        }
        val count = now.count { it.actionable }
        return if (count >= 2) "$first 확인할 것이 ${count}가지입니다." else first
    }

    /** T-09 */
    fun returnDone(): String = "반납 기록을 남겼습니다. 화면의 QR로 같은 기록을 받으세요."

    /** T-10. 대여 중에 말해도 되는 유일한 안내 */
    fun lowBattery(percent: Int): String = "배터리가 ${percent}퍼센트 남았습니다. 가까운 충전소를 경유지로 추가할까요?"

    /** T-11 */
    fun arrived(): String = "반납 지점에 도착했습니다. 반납 체크를 시작할까요?"

    /** 숫자는 한 자리를 1.5음절로 센다(82 → 팔십이). */
    fun estimatedSeconds(text: String): Double = text.sumOf { c ->
        when {
            c in '가'..'힣' -> 1.0
            c.isDigit() -> 1.5
            c.isLetter() -> 1.0
            else -> 0.0
        }
    } / SYLLABLES_PER_SECOND

    // "조수석 창문이 열려 있고, 실내등이 켜져 있습니다."
    private fun openClause(items: List<CheckItem>): String {
        val stems = items.map { "${it.subject.josa("이", "가")} ${if (it is CheckItem.LightOn) "켜져" else "열려"} 있" }
        return stems.dropLast(1).joinToString("") { "${it}고, " } + stems.last() + "습니다."
    }

    private fun seatSentence(seats: List<CheckItem.SeatDetected>): String =
        "${seats.joinToString { it.area.label }}에 무언가 감지됩니다. 두고 내리는 물건이 없는지 확인해 주세요."

    private fun energySentence(drop: CheckItem.EnergyDrop): String =
        "${drop.item.label.josa("은", "는")} 인수 때 ${drop.before}퍼센트, 지금 ${drop.after}퍼센트입니다."

    private fun more(count: Int) = "외 ${count}가지는 화면을 확인해 주세요."
}

/** 마지막 글자에 받침이 있으면 [withFinal], 없으면 [withoutFinal]을 붙인다. 예: "창문" → "창문이", "배터리" → "배터리가" */
internal fun String.josa(withFinal: String, withoutFinal: String): String {
    val last = lastOrNull { it in '가'..'힣' } ?: return this + withoutFinal
    return this + if ((last - '가') % 28 != 0) withFinal else withoutFinal
}
