package app.chagok.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.core.inspect.ComparisonRow
import app.chagok.core.inspect.Verdict
import app.chagok.core.model.StateItem
import app.chagok.core.rental.PickupFact
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalReport
import java.time.Instant

/**
 * V-06 반납 리포트. 차 화면, 고객 폰, 운영자 웹이 같은 기록 번호와 무결성 값을 보여 준다.
 * 결론을 맨 위에 크게 두고(고객 웹과 같은 문장), QR은 운전석 쪽에 크게 둔다. 둘은 스크롤 없이 함께 보인다.
 * '폰으로 받았습니다'를 누르거나 입력 없이 3분이 지나면 QR을 내린다. 내려도 'QR 다시 보기'로 돌아올 수 있다.
 * QR을 내린 뒤 다시 입력 없이 3분이 지나면 리포트를 닫는다. 고객이 닫지 않고 내려도 다음 고객이 앞 기록을 다시 열 수 없게 한다. 저장된 기록은 직원 목록(PIN)에 남는다.
 */
@Composable
internal fun ReportView(report: RentalReport, url: String?, onClose: () -> Unit) {
    var qrShown by rememberSaveable(report.hash) { mutableStateOf(true) }
    if (qrShown) AfterIdle { qrShown = false } else AfterIdle(onClose)
    val wide = LocalWide.current
    WithQr(
        url = url,
        qrShown = qrShown,
        actions = {
            ActionRow {
                if (qrShown) {
                    ChagokButton("폰으로 받았습니다", { qrShown = false })
                    ChagokButton("닫기", onClose, kind = ButtonKind.Secondary)
                } else {
                    ChagokButton("닫기", onClose, modifier = Modifier.widthIn(min = 420.dp))
                    ChagokButton("QR 다시 보기", { qrShown = true }, kind = ButtonKind.Secondary, icon = R.drawable.ic_qr)
                }
            }
        },
        head = {
            Conclusion(report)
            if (wide || !qrShown) ReportStamp(report)
        },
        rest = {
            if (!wide && qrShown) ReportStamp(report)
            ReportDetails(report)
        },
    )
}

// 고객 웹처럼 숫자는 굵은 잉크로, 잇는 말은 한 단 낮춘다. *로 감싼 부분이 강조다.
@Composable
private fun Conclusion(report: RentalReport) {
    val c = ChagokTheme.colors
    report.conclusion.forEach { line ->
        val warn = report.comparison.count(Verdict.NEEDS_CHECK) > 0 && line.startsWith("확인 필요 *")
        Text(
            buildAnnotatedString {
                line.split('*').forEachIndexed { i, part ->
                    if (i % 2 == 1) {
                        withStyle(SpanStyle(color = if (warn) c.signalInk else c.ink, fontWeight = FontWeight.SemiBold)) { append(part) }
                    } else {
                        append(part)
                    }
                }
            },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal).tabular,
            color = if (warn) c.signalInk else c.ink2,
        )
    }
}

// 기간, 기록 번호, 무결성 값, 직원 기록 표시
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportStamp(report: RentalReport) {
    val c = ChagokTheme.colors
    Spacer(Modifier.height(12.dp))
    Text(
        "반납 기록을 남겼습니다  ·  ${span(report.pickup.capturedAt, report.returned.capturedAt)}",
        style = MaterialTheme.typography.bodyMedium.tabular,
        color = c.ink2,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("기록 번호 ${report.recordNo}", style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
        IntegrityValue(report.hash)
        if (report.byStaff) Tag("직원 기록", color = c.ink, container = c.sunken)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportDetails(report: RentalReport) {
    val c = ChagokTheme.colors
    val rows = report.comparison.rows
    fun own(row: ComparisonRow) = row.notes.filterNot { RentalReport.PICKUP_MARK in it }
    fun onlyShield(row: ComparisonRow) = row.verdict == Verdict.NOTE && row.notes.isNotEmpty() && own(row).isEmpty()
    val shields = report.fromPickup
    val needs = rows.filter { it.verdict == Verdict.NEEDS_CHECK }
    val notes = rows.filter { it.verdict == Verdict.NOTE && !onlyShield(it) }
    val unchanged = rows.filter { it.verdict == Verdict.UNCHANGED }
    val unknown = rows.filter { it.verdict == Verdict.UNKNOWN }

    if (shields.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shields.forEach { ShieldCard(it, report.pickup.capturedAt) }
        }
    }
    Spacer(Modifier.height(20.dp))
    Note(R.drawable.ic_info, RentalReport.DISCLAIMER)
    Spacer(Modifier.height(24.dp))
    Overview(needs.size, rows.count(::onlyShield), notes.size, unchanged.size, unknown.size)
    if (needs.isNotEmpty()) {
        SectionTitle(Verdict.NEEDS_CHECK.label, Modifier.padding(top = 32.dp, bottom = 4.dp), needs.size, c.signalInk, R.drawable.ic_warning)
        needs.forEach { ReportRow(it, own(it)) }
    }
    if (notes.isNotEmpty()) {
        SectionTitle(Verdict.NOTE.label, Modifier.padding(top = 32.dp, bottom = 4.dp), notes.size)
        notes.forEach { ReportRow(it, own(it)) }
    }
    RentalTimeline(report)
    if (unchanged.isNotEmpty()) {
        SectionTitle(Verdict.UNCHANGED.label, Modifier.padding(top = 32.dp, bottom = 16.dp), unchanged.size)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            unchanged.forEach { Tag(it.item.label, color = c.ink2, container = c.sunken, icon = it.item.icon()) }
        }
    }
    if (unknown.isNotEmpty()) {
        SectionTitle(Verdict.UNKNOWN.label, Modifier.padding(top = 32.dp, bottom = 4.dp), unknown.size)
        unknown.forEach { ReportRow(it, it.notes) }
    }
    Spacer(Modifier.height(24.dp))
}

// 인수 때부터 있던 것. 고객을 지켜 주는 장면이라 결론 바로 아래에 초록 방패로 둔다. 인수 시각은 실제 기록의 시각이다.
@Composable
private fun ShieldCard(fact: PickupFact, pickupAt: Instant) {
    val c = ChagokTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.goodSoft, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_shield), contentDescription = "인수 때부터", tint = c.good, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(fact.text, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(fact.recordedAt(time(pickupAt)), style = MaterialTheme.typography.bodyMedium.tabular, color = c.good)
        }
    }
}

// 고객 웹의 막대와 같다. SHIELD는 문구가 모두 '(인수 때부터)'인 참고 줄이다. 데이터의 분류가 아니라 화면이 나눠 보여 주는 칸이다.
private enum class Cell(val label: String) {
    NEEDS_CHECK("확인 필요"),
    SHIELD("인수 때부터"),
    NOTE("참고"),
    UNCHANGED("변화 없음"),
    UNKNOWN("확인 불가"),
}

// 꽉 찬 칸은 확인 필요·인수 때부터·변화 없음, 테두리 칸은 참고, 점선 칸은 확인 불가다.
private fun DrawScope.cell(kind: Cell, c: ChagokColors, topLeft: Offset, size: Size, radius: Float) {
    val corner = CornerRadius(radius)
    val inset = 1.5.dp.toPx()
    val inner = Size(size.width - inset * 2, size.height - inset * 2)
    val innerAt = topLeft + Offset(inset, inset)
    when (kind) {
        Cell.NEEDS_CHECK -> drawRoundRect(c.signal, topLeft, size, corner)
        Cell.SHIELD -> drawRoundRect(c.good, topLeft, size, corner)
        Cell.UNCHANGED -> drawRoundRect(c.ink2.copy(alpha = 0.35f), topLeft, size, corner)
        Cell.NOTE -> drawRoundRect(c.ink2, innerAt, inner, corner, style = Stroke(inset * 2))
        Cell.UNKNOWN -> drawRoundRect(
            c.ink3, innerAt, inner, corner,
            style = Stroke(inset * 2, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
}

/** 분류별 개수. 막대 한 칸이 확인한 항목 하나다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(needs: Int, shields: Int, notes: Int, unchanged: Int, unknown: Int) {
    val c = ChagokTheme.colors
    val counts = listOf(Cell.NEEDS_CHECK to needs, Cell.SHIELD to shields, Cell.NOTE to notes, Cell.UNCHANGED to unchanged, Cell.UNKNOWN to unknown)
    val cells = counts.flatMap { (kind, n) -> List(n) { kind } }
    if (cells.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (cells.size - 1)) / cells.size
        cells.forEachIndexed { i, kind -> cell(kind, c, Offset(i * (w + gap), 0f), Size(w, size.height), 6.dp.toPx()) }
    }
    Spacer(Modifier.height(14.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        counts.filter { (kind, n) -> kind != Cell.SHIELD || n > 0 }.forEach { (kind, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(20.dp)) { cell(kind, c, Offset.Zero, size, 5.dp.toPx()) }
                Spacer(Modifier.width(10.dp))
                Text(kind.label, style = MaterialTheme.typography.bodySmall, color = c.ink2)
                Spacer(Modifier.width(8.dp))
                Text("$n", style = MaterialTheme.typography.titleMedium.tabular, color = c.ink)
            }
        }
    }
}

/** 리포트 한 줄. 규칙이 쓴 문구가 항목 이름을 담고 있어 첫 문구를 제목으로 쓴다. 잔량은 막대를 곁들인다. */
@Composable
private fun ReportRow(row: ComparisonRow, notes: List<String>) {
    val c = ChagokTheme.colors
    val attention = row.verdict == Verdict.NEEDS_CHECK
    val lines = if (row.verdict == Verdict.UNKNOWN) listOf("${row.item.label}  ·  ${notes.joinToString()}") else notes
    val before = row.before?.removeSuffix("%")?.toIntOrNull()
    val after = row.after?.removeSuffix("%")?.toIntOrNull()
    val energy = row.item == StateItem.FUEL || row.item == StateItem.EV_SOC
    Column {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            IconBadge(
                row.item.icon(),
                tint = if (attention) c.signalInk else c.ink2,
                container = if (attention) c.signalSoft else c.sunken,
                size = 56.dp,
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                lines.firstOrNull()?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium.tabular, color = if (row.verdict == Verdict.UNKNOWN) c.ink2 else c.ink)
                }
                lines.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodySmall.tabular, color = c.ink2) }
                if (energy && after != null) {
                    Spacer(Modifier.height(6.dp))
                    EnergyBar(after = after, before = before, modifier = Modifier.widthIn(max = 560.dp))
                }
            }
        }
        HorizontalDivider(color = c.rule)
    }
}

// 대여 중 기록: 변화와 공백을 시간순으로 함께 적는다(고객 웹과 같다). 공백은 변화 없음이 아니라 모르는 구간이라고 사실대로 적는다.
@Composable
private fun RentalTimeline(report: RentalReport) {
    val c = ChagokTheme.colors
    SectionTitle("대여 중 기록", Modifier.padding(top = 32.dp, bottom = 8.dp), report.timeline.size + report.timelineMore)
    val lines: List<Pair<Instant, @Composable () -> Unit>> =
        report.timeline.map { entry ->
            entry.at to @Composable {
                TimelineLine(time(entry.at)) {
                    Text(entry.note, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        } + report.gaps.map { gap ->
            gap.from to @Composable {
                TimelineLine(span(gap.from, gap.to)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(painterResource(R.drawable.ic_history_off), contentDescription = null, tint = c.ink2, modifier = Modifier.padding(top = 4.dp).size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(Rental.GAP_NOTE, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    }
                }
            }
        }
    lines.sortedBy { it.first }.forEach { (_, line) -> line() }
    if (report.timelineMore > 0) {
        Spacer(Modifier.height(10.dp))
        Text("외 ${report.timelineMore}건은 이 차의 기록에 있습니다.", style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
    }
    if (lines.isEmpty()) Text(Rental.timelineEmpty(report.pickup.state), style = MaterialTheme.typography.bodyMedium, color = c.ink2)
}

@Composable
private fun TimelineLine(at: String, content: @Composable () -> Unit) {
    val c = ChagokTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(at, style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2, modifier = Modifier.width(290.dp))
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f)) { content() }
        }
        HorizontalDivider(color = c.rule)
    }
}
