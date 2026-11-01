package app.chagok.core.rental

import app.chagok.core.inspect.Comparison
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.Inspector
import app.chagok.core.inspect.SEOUL
import app.chagok.core.inspect.TIME
import app.chagok.core.inspect.Verdict
import app.chagok.core.inspect.compare
import app.chagok.core.inspect.korean
import app.chagok.core.model.LoggedEvent
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.source.VehicleSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toKotlinDuration

/** 인수·반납 기록을 만든다. 렌터카에서 남기지 않는 항목은 저장하기 전에 지우고, 값이 없는 항목에는 이유를 붙인다. */
class RentalRecorder(
    private val vehicle: VehicleSource,
    private val vehicleId: String,
    private val appVersion: String,
    private val demo: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun capture(sessionId: String, recordNo: String, kind: SnapshotKind): SnapshotRecord = SnapshotRecord(
        sessionId = sessionId,
        recordNo = recordNo,
        kind = kind,
        vehicleId = vehicleId,
        capturedAt = now(),
        state = vehicle.state().forRecord(Rental.RECORDED),
        items = Rental.RECORDED,
        appVersion = appVersion,
        rulesVersion = Rental.RULES_VERSION,
        demo = demo,
    )
}

/**
 * 반납 리포트. 인수·반납 기록을 렌터카 규칙으로 비교한 결과이고, 무결성 값에는 두 기록의 무결성 값이 함께 묶인다.
 * 차 화면, 고객 리포트, 운영자 웹은 모두 이 리포트의 [hash]를 보여 준다.
 */
data class RentalReport(
    val pickup: SnapshotRecord,
    val returned: SnapshotRecord,
    val policy: RentalPolicy,
    val comparison: Comparison,
) {
    val recordNo: String get() = pickup.recordNo
    val demo: Boolean get() = pickup.demo || returned.demo

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("sessionId", pickup.sessionId)
        put("recordNo", recordNo)
        put("vehicleId", pickup.vehicleId)
        put("rules", returned.rulesVersion)
        put("policy", policy.version)
        put("demo", demo)
        put("pickup", buildJsonObject { put("at", pickup.capturedAt.toString()); put("hash", pickup.hash) })
        put("return", buildJsonObject { put("at", returned.capturedAt.toString()); put("hash", returned.hash) })
        put("counts", buildJsonObject { Verdict.entries.forEach { put(it.name, comparison.count(it)) } })
        put(
            "rows",
            buildJsonArray {
                comparison.rows.forEach { row ->
                    add(
                        buildJsonObject {
                            put("item", row.item.key)
                            put("verdict", row.verdict.name)
                            put("before", row.before)
                            put("after", row.after)
                            put("notes", JsonArray(row.notes.map(::JsonPrimitive)))
                        },
                    )
                }
            },
        )
        put("notApplicable", JsonArray(comparison.notApplicable.map { JsonPrimitive(it.key) }))
    }

    // 화면이 다시 그려질 때마다 읽으므로 한 번만 계산한다.
    val hash: String by lazy { Canonical.sha256(Canonical.encode(toJson())) }

    /** 공유·인쇄용 평문 */
    fun toText(zone: ZoneId = SEOUL): String = buildString {
        val from = TIME.format(pickup.capturedAt.atZone(zone))
        val to = TIME.format(returned.capturedAt.atZone(zone))
        val duration = java.time.Duration.between(pickup.capturedAt, returned.capturedAt).toKotlinDuration()
        appendLine("반납 리포트 · $recordNo" + if (demo) " · 시연 데이터 포함" else "")
        appendLine("$from → $to (${duration.korean()})")
        appendLine(Verdict.entries.joinToString(" · ") { "${it.label} ${comparison.count(it)}" })
        appendLine(DISCLAIMER)
        for (verdict in listOf(Verdict.NEEDS_CHECK, Verdict.NOTE)) {
            val rows = comparison.rows.filter { it.verdict == verdict }
            if (rows.isEmpty()) continue
            appendLine()
            appendLine(verdict.label)
            rows.flatMap { it.notes }.forEach { appendLine("- $it") }
        }
        comparison.rows.filter { it.verdict == Verdict.UNCHANGED }.takeIf { it.isNotEmpty() }?.let { rows ->
            appendLine()
            appendLine(Verdict.UNCHANGED.label)
            appendLine(rows.joinToString { it.item.label })
        }
        comparison.rows.filter { it.verdict == Verdict.UNKNOWN }.takeIf { it.isNotEmpty() }?.let { rows ->
            appendLine()
            appendLine(Verdict.UNKNOWN.label)
            rows.forEach { appendLine("- ${it.item.label}: ${it.notes.joinToString()}") }
        }
        appendLine()
        append("무결성 ${Canonical.short(hash)}")
    }

    companion object {
        /**
         * report/2: 줄마다 붙던 화면 이름(label)을 뺐다. 이름은 item 키로 정해지는 화면 문구라 기록에 둘 필요가 없고,
         * 빼면 데이터 QR이 약 10% 작아진다(시연 리포트 994B → 897B). 리포트 웹은 report/1도 계속 읽는다.
         */
        const val SCHEMA = "report/2"

        /** 리포트 첫머리에 고정으로 넣는 문장 */
        const val DISCLAIMER = "'확인 필요'는 업체가 직접 볼 항목이며, 고객 책임이라는 뜻이 아닙니다."

        fun build(
            pickup: SnapshotRecord,
            returned: SnapshotRecord,
            policy: RentalPolicy = RentalPolicy(),
            events: List<LoggedEvent>? = null,
        ): RentalReport {
            require(pickup.kind == SnapshotKind.PICKUP && returned.kind == SnapshotKind.RETURN) { "인수 기록과 반납 기록이 필요합니다" }
            require(pickup.sessionId == returned.sessionId) { "같은 대여의 기록이 아닙니다" }
            val report = Inspector(Rental.rules(policy))
                .inspect(InspectionContext(pickup.toSnapshot(), returned.toSnapshot(), events))
            return RentalReport(pickup, returned, policy, report.compare(Rental.REPORT_ORDER))
        }
    }
}
