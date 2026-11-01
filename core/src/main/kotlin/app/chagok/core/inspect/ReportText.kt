package app.chagok.core.inspect

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

internal val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** 공유·첨부용 평문 리포트. */
fun InspectionReport.toText(zone: ZoneId = SEOUL): String = buildString {
    val from = TIME.format(context.before.capturedAt.atZone(zone))
    val to = TIME.format(context.after.capturedAt.atZone(zone))
    appendLine("점검 리포트")
    appendLine("$from → $to (${context.duration.korean()})")
    if (findings.isEmpty()) {
        appendLine()
        appendLine("특이사항 없음")
    }
    section("확인 필요", findings.filter { it.severity == Severity.WARNING })
    section("참고", findings.filter { it.severity == Severity.INFO })
}.trimEnd()

private fun StringBuilder.section(title: String, items: List<Finding>) {
    if (items.isEmpty()) return
    appendLine()
    appendLine(title)
    items.forEach { appendLine("- ${it.message}") }
}

/** 90분 → "1시간 30분", 3일 2시간 → "3일 2시간" */
internal fun Duration.korean(): String {
    val minutes = inWholeMinutes
    val days = minutes / (60 * 24)
    val hours = minutes / 60 % 24
    val mins = minutes % 60
    return buildList {
        if (days > 0) add("${days}일")
        if (hours > 0) add("${hours}시간")
        if (mins > 0 || isEmpty()) add("${mins}분")
    }.joinToString(" ")
}
