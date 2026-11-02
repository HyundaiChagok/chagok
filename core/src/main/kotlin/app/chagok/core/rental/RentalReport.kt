package app.chagok.core.rental

import app.chagok.core.inspect.Comparison
import app.chagok.core.inspect.ComparisonRow
import app.chagok.core.inspect.InspectionContext
import app.chagok.core.inspect.Inspector
import app.chagok.core.inspect.SEOUL
import app.chagok.core.inspect.TIME
import app.chagok.core.inspect.Verdict
import app.chagok.core.inspect.compare
import app.chagok.core.inspect.display
import app.chagok.core.inspect.korean
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleState
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.record.text
import app.chagok.core.source.VehicleSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.toKotlinDuration

/**
 * 인수·반납 기록을 만든다. 렌터카에서 남기지 않는 항목은 저장하기 전에 지우고, 값이 없는 항목에는 이유를 붙인다.
 * 차 식별은 차대번호를 먼저 쓴다. 읽지 못하면 [vehicleId](업체 등록 번호)로 대신하고 그 사실을 기록에 남긴다.
 */
class RentalRecorder(
    private val vehicle: VehicleSource,
    private val vehicleId: String,
    private val appVersion: String,
    private val demo: Boolean = false,
    private val source: String? = null,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun capture(sessionId: String, recordNo: String, kind: SnapshotKind): SnapshotRecord {
        val vin = vehicle.vin()
        return SnapshotRecord(
            sessionId = sessionId,
            recordNo = recordNo,
            kind = kind,
            vehicleId = vin ?: vehicleId,
            // 초 아래 자리는 뜻이 없고 데이터 QR만 키운다.
            capturedAt = now().truncatedTo(ChronoUnit.SECONDS),
            state = vehicle.state().forRecord(Rental.RECORDED),
            items = Rental.RECORDED,
            appVersion = appVersion,
            rulesVersion = Rental.RULES_VERSION,
            demo = demo,
            vehicleIdFallback = vin == null,
            source = source,
        )
    }
}

/** 인수 때부터 있던 것 한 줄. [text]는 '(인수 때부터)'를 뺀 문구다. */
data class PickupFact(val item: StateItem, val text: String) {
    /** 인수 시각과 함께 고객을 지켜 주는 문장을 만든다. 예: 이 경고등은 11.01 14:50 인수 기록에 있습니다. */
    fun recordedAt(time: String): String = "이 ${if ("경고등" in text) "경고등" else "항목"}은 $time 인수 기록에 있습니다."
}

/**
 * 반납 리포트. 인수·반납 기록을 렌터카 규칙으로 비교한 결과이고, 무결성 값에는 두 기록의 무결성 값이 함께 묶인다.
 * 차 화면, 고객 리포트, 운영자 웹은 모두 이 리포트의 [hash]를 보여 준다.
 *
 * [timeline]은 대여 중 변화를 시간순으로 [MAX_TIMELINE]건까지 담고, 나머지는 [timelineMore]로 센다. 전체는 차 안의
 * timeline.jsonl에 남는다. [gaps]는 변화를 볼 수 없던 구간이다. 셋 다 무결성 값에 묶인다.
 *
 * [recordedBy]는 고객의 반납 체크 없이 남긴 기록이면 그 사실이다(지금은 [STAFF]뿐이다). 있을 때만 JSON에 들어가 이전 리포트의 무결성 값은 그대로이고, 무결성 값에 묶인다.
 */
data class RentalReport(
    val pickup: SnapshotRecord,
    val returned: SnapshotRecord,
    val policy: RentalPolicy,
    val comparison: Comparison,
    val timeline: List<TimelineEntry> = emptyList(),
    val timelineMore: Int = 0,
    val gaps: List<RecordGap> = emptyList(),
    val recordedBy: String? = null,
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
        if (pickup.vehicleIdFallback) put(SnapshotRecord.KEY_NO_VIN, true)
        (pickup.source ?: returned.source)?.let { put(SnapshotRecord.KEY_SOURCE, it) }
        recordedBy?.let { put(KEY_RECORDED_BY, it) }
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
        if (timeline.isNotEmpty()) {
            put(
                "timeline",
                buildJsonArray {
                    timeline.forEach { entry ->
                        add(buildJsonObject { put("at", entry.at.toString()); put("note", entry.note) })
                    }
                },
            )
        }
        if (timelineMore > 0) put("timelineMore", timelineMore)
        if (gaps.isNotEmpty()) put("gaps", gapsJson(gaps))
    }

    // 화면이 다시 그려질 때마다 읽으므로 한 번만 계산한다.
    val hash: String by lazy { Canonical.sha256(Canonical.encode(toJson())) }

    /** 직원이 반납 체크 없이 남긴 기록인지 */
    val byStaff: Boolean get() = recordedBy == STAFF

    /** '(인수 때부터)'가 붙은 문구. 줄이 아니라 문구 단위로 본다. 경고등 한 줄에 새 경고등과 인수 때부터 켜진 경고등이 함께 올 수 있다(고객 웹과 같다). */
    val fromPickup: List<PickupFact>
        get() = comparison.rows.flatMap { row ->
            row.notes.filter { PICKUP_MARK in it }.map { PickupFact(row.item, it.replace(" $PICKUP_MARK", "")) }
        }

    /**
     * 리포트의 결론. 고객 웹(web/r)의 첫 문장과 같은 문장이고, 한 칸이 한 줄이다. *로 감싼 부분이 강조다. [conclusionText]는 강조 없는 평문이다.
     * 잔량이 있으면 그 변화를 먼저 말하고, 금액이 있으면 금액을, 없으면 확인 필요 건수를 말한다.
     */
    val conclusion: List<String>
        get() {
            val energy = comparison.rows.filter { it.item == StateItem.FUEL || it.item == StateItem.EV_SOC }
            val amounts = energy.mapNotNull { row -> row.notes.firstNotNullOfOrNull { WON.find(it) }?.let { row.item.label to it.groupValues[1] } }
            val needs = comparison.count(Verdict.NEEDS_CHECK)
            return listOfNotNull(
                energy.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ",") { "${it.item.label} *${it.change()}*" },
                when {
                    amounts.isNotEmpty() -> "참고 정산" + (if (policy.demo) "(${policy.basis}) " else " ") + amounts.joinToString { (name, won) -> (if (amounts.size > 1) "$name " else "") + "*약 ${won}원*" } + "입니다."
                    needs > 0 -> "확인 필요 *${needs}건*입니다."
                    else -> "확인 필요한 항목이 없습니다."
                },
            )
        }

    val conclusionText: String get() = conclusion.joinToString(" ").replace("*", "")

    private fun ComparisonRow.change(): String = when {
        before != null && after != null && before != after -> "$before → $after"
        after != null && before == after -> "$after 그대로"
        verdict == Verdict.UNCHANGED -> "변화 없음"
        after != null -> "반납 $after"
        else -> "확인 불가"
    }

    /** 공유·인쇄용 평문 */
    fun toText(zone: ZoneId = SEOUL): String = buildString {
        val from = TIME.format(pickup.capturedAt.atZone(zone))
        val to = TIME.format(returned.capturedAt.atZone(zone))
        val duration = java.time.Duration.between(pickup.capturedAt, returned.capturedAt).toKotlinDuration()
        appendLine(
            "반납 리포트 · $recordNo" + when {
                demo -> " · 시연 데이터 포함"
                pickup.source == SnapshotRecord.SOURCE_EMULATOR -> " · 에뮬레이터 값"
                else -> ""
            } + if (byStaff) " · 직원 기록" else "",
        )
        appendLine(if (Rental.underAMinute(pickup.capturedAt, returned.capturedAt)) "$from (${Rental.UNDER_A_MINUTE})" else "$from → $to (${duration.korean()})")
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
        if (timeline.isNotEmpty()) {
            appendLine()
            appendLine("대여 중 변화")
            timeline.forEach { appendLine("- ${TIME.format(it.at.atZone(zone))} ${it.note}") }
            if (timelineMore > 0) appendLine("- 외 ${timelineMore}건")
        }
        if (gaps.isNotEmpty()) {
            appendLine()
            appendLine("기록 공백")
            gaps.forEach { gap ->
                val start = TIME.format(gap.from.atZone(zone))
                appendLine(if (Rental.underAMinute(gap.from, gap.to)) "- $start (${Rental.UNDER_A_MINUTE})" else "- $start → ${TIME.format(gap.to.atZone(zone))}")
            }
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

        /** [recordedBy] 값: 직원이 반납 체크 없이 남긴 기록(고객이 차를 두고 떠난 경우) */
        const val STAFF = "staff"

        /** 규칙이 인수 때부터 있던 것에 붙이는 표시. 고객을 지켜 주는 사실이라 화면과 고객 웹이 초록 방패로 따로 보여 준다. */
        const val PICKUP_MARK = "(인수 때부터)"

        internal const val KEY_RECORDED_BY = "recordedBy"

        private val WON = Regex("""참고 정산.*?([\d,]+)원""")

        /** 리포트(데이터 QR)에 담는 대여 중 변화의 최대 건수 */
        const val MAX_TIMELINE = 5

        /** 리포트 첫머리에 고정으로 넣는 문장 */
        const val DISCLAIMER = "'확인 필요'는 업체가 직접 볼 항목이며, 고객 책임이라는 뜻이 아닙니다."

        fun build(
            pickup: SnapshotRecord,
            returned: SnapshotRecord,
            policy: RentalPolicy = RentalPolicy(),
            events: List<LoggedEvent>? = null,
            gaps: List<RecordGap> = emptyList(),
            recordedBy: String? = null,
        ): RentalReport {
            require(pickup.kind == SnapshotKind.PICKUP && returned.kind == SnapshotKind.RETURN) { "인수 기록과 반납 기록이 필요합니다" }
            require(pickup.sessionId == returned.sessionId) { "같은 대여의 기록이 아닙니다" }
            val report = Inspector(Rental.rules(policy))
                .inspect(InspectionContext(pickup.toSnapshot(), returned.toSnapshot(), events))
            val timeline = Rental.timeline(pickup.state, events.orEmpty())
            return RentalReport(
                pickup, returned, policy, report.compare(Rental.REPORT_ORDER).withTireLampOnce(pickup.state, returned.state),
                timeline = timeline.take(MAX_TIMELINE),
                timelineMore = (timeline.size - MAX_TIMELINE).coerceAtLeast(0),
                gaps = Rental.gaps(events.orEmpty(), gaps, returned.capturedAt),
                recordedBy = recordedBy,
            )
        }

        /**
         * 저장한 리포트([toJson]에 hash를 더한 것)를 되살린다. 규칙을 다시 돌리지 않는다. 앱이 업데이트돼 규칙이나 업체 기준이
         * 바뀌어도 그때 만든 리포트는 그대로여야 하기 때문이다. 업체 기준은 리포트에 버전만 남으므로 버전만 되살린다.
         * hash가 없거나 내용과 맞지 않으면 받지 않는다.
         */
        fun fromJson(json: JsonObject, pickup: SnapshotRecord, returned: SnapshotRecord): RentalReport {
            require(json.text("schema") == SCHEMA) { "모르는 리포트 형식: ${json["schema"]}" }
            val keys = StateItem.entries.associateBy { it.key }
            val rows = json.getValue("rows").jsonArray.map { element ->
                val row = element.jsonObject
                ComparisonRow(
                    item = keys.getValue(row.text("item")),
                    before = row["before"]?.jsonPrimitive?.contentOrNull,
                    after = row["after"]?.jsonPrimitive?.contentOrNull,
                    verdict = Verdict.valueOf(row.text("verdict")),
                    notes = row.getValue("notes").jsonArray.map { it.jsonPrimitive.content },
                )
            }
            val report = RentalReport(
                pickup, returned, RentalPolicy(version = json.text("policy")),
                Comparison(rows, json.getValue("notApplicable").jsonArray.map { keys.getValue(it.jsonPrimitive.content) }),
                timeline = json["timeline"]?.jsonArray.orEmpty().map { element ->
                    element.jsonObject.let { TimelineEntry(Instant.parse(it.text("at")), it.text("note")) }
                },
                timelineMore = json["timelineMore"]?.jsonPrimitive?.int ?: 0,
                gaps = json["gaps"]?.jsonArray.orEmpty().map { element ->
                    element.jsonObject.let { RecordGap(Instant.parse(it.text("from")), Instant.parse(it.text("to"))) }
                },
                recordedBy = json[KEY_RECORDED_BY]?.jsonPrimitive?.contentOrNull,
            )
            require(json["hash"]?.jsonPrimitive?.content == report.hash) { "무결성 값이 리포트 내용과 맞지 않습니다" }
            // 기록 번호처럼 인수·반납 기록에서 다시 만드는 값도 저장된 내용과 같아야 한다.
            require(Canonical.encode(JsonObject(json - "hash")) == Canonical.encode(report.toJson())) { "리포트 내용이 기록과 맞지 않습니다" }
            return report
        }

        /**
         * TPMS 경고등은 타이어 줄이 말한다(규칙에서 뺐다). 경고등 줄의 값에서도 빼지 않으면 '변화 없음'인 줄에 켜진 경고등 이름이 보인다.
         * TPMS 말고 달라진 것이 없는데 TPMS만 켜지거나 꺼졌으면 경고등 줄을 만들지 않는다. '경고등 변화 없음'이라고 적으면 타이어 줄과 어긋나기 때문이다.
         */
        private fun Comparison.withTireLampOnce(before: VehicleState, after: VehicleState): Comparison {
            val was = before.tirePressureLamp
            val now = after.tirePressureLamp
            if (was != true && now != true) return this
            val changed = was != null && now != null && was != now
            fun VehicleState.others() = copy(telltales = telltales?.filterNot { it.code == Telltale.TPMS }?.toSet()).display(StateItem.TELLTALES)
            return copy(
                rows = rows.mapNotNull { row ->
                    when {
                        row.item != StateItem.TELLTALES -> row
                        changed && row.verdict == Verdict.UNCHANGED -> null
                        else -> row.copy(before = before.others(), after = after.others())
                    }
                },
            )
        }

        internal fun gapsJson(gaps: List<RecordGap>): JsonArray = buildJsonArray {
            gaps.forEach { gap -> add(buildJsonObject { put("from", gap.from.toString()); put("to", gap.to.toString()) }) }
        }
    }
}
