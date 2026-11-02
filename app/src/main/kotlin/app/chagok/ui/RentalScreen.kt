package app.chagok.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.core.model.VehicleState
import app.chagok.core.rental.Rental
import app.chagok.core.rental.ReturnPlan
import app.chagok.core.rental.ReturnTrip
import app.chagok.rental.RentalLog
import app.chagok.rental.RentalUi
import app.chagok.rental.ReturnTripStep

/**
 * V-04 대여 중. 기록 중임과 지금까지의 대여 중 기록 건수·공백을 짧게 보여 준다. '반납하러 가기'와 '반납 체크'는 정차 중에만 보인다. 주행 중에는 아무것도 묻지 않는다.
 * '반납하러 가기'는 내비에 연결됐을 때만 보이고, 도착 예상 배터리를 받으면 비교([ReturnPlanPanel])와 그 결정 버튼으로 바뀐다.
 * 도착하면 제목이 바뀌고 '반납 체크 시작'이 주 버튼이 된다.
 */
@Composable
internal fun InRental(
    ui: RentalUi,
    onReturnCheck: () -> Unit,
    onShowPickup: () -> Unit,
    onGoToReturn: () -> Unit,
    onStartRoute: () -> Unit,
    onChargers: () -> Unit,
    onClosePlan: () -> Unit,
) {
    val c = ChagokTheme.colors
    val pickup = ui.pickup
    val step = ui.returnTrip
    val plan = (step as? ReturnTripStep.Planned)?.plan
    WithMap(
        map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), description = "차 그림") },
        lead = {
            RecordingLine(ui.log, pickup?.state)
            Spacer(Modifier.height(20.dp))
            Text(if (ui.arrived) ReturnTrip.ARRIVED_TITLE else "대여 중", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            pickup?.let {
                Spacer(Modifier.height(8.dp))
                Text("인수 ${time(it.capturedAt)}  ·  기록 번호 ${it.recordNo}", style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
            }
        },
        actions = {
            ActionRow {
                when {
                    plan != null -> {
                        ChagokButton("경로 안내 시작", onStartRoute, modifier = Modifier.widthIn(min = 360.dp))
                        if (plan.lower) ChagokButton("근처 충전소 보기", onChargers, kind = ButtonKind.Secondary)
                        ChagokButton("닫기", onClosePlan, kind = ButtonKind.Secondary)
                    }
                    !ui.parked -> ParkFirst("정차하면 반납 체크를 할 수 있습니다")
                    else -> {
                        val canGo = ui.naviReady && !ui.arrived
                        if (canGo) ChagokButton("반납하러 가기", onGoToReturn, enabled = step != ReturnTripStep.Estimating, modifier = Modifier.widthIn(min = 360.dp))
                        ChagokButton(
                            if (ui.arrived) "반납 체크 시작" else "반납 체크",
                            onReturnCheck,
                            kind = if (canGo) ButtonKind.Secondary else ButtonKind.Primary,
                            modifier = if (canGo) Modifier else Modifier.widthIn(min = 420.dp),
                        )
                        if (pickup != null) ChagokButton("인수 기록 보기", onShowPickup, kind = ButtonKind.Secondary)
                    }
                }
            }
        },
    ) {
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).fillMaxWidth().fadeBottom(scroll).verticalScroll(scroll)) {
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note(R.drawable.ic_lock, "위치와 운전 습관은 기록하지 않습니다.")
                if (ui.restored) {
                    Note(R.drawable.ic_info, "앱을 다시 켜서 같은 대여를 이어 갑니다. 꺼져 있던 구간은 공백으로 남습니다.")
                }
                pickup?.state?.telltales?.takeIf { it.isNotEmpty() }?.let { lit ->
                    ShieldNote("인수 때 켜져 있던 경고등 ${lit.size}개는 인수 기록에 있습니다.")
                }
                if (!ui.arrived && step == null) Note(R.drawable.ic_info, "반납 지점에 도착해 정차하면 반납 체크를 눌러 주세요.")
            }
            when (step) {
                null -> Unit
                ReturnTripStep.Estimating -> {
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = c.ink2, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(ReturnTrip.ESTIMATING, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    }
                }
                is ReturnTripStep.Planned -> {
                    Spacer(Modifier.height(28.dp))
                    ReturnPlanPanel(step.plan)
                }
                is ReturnTripStep.Guiding -> {
                    Spacer(Modifier.height(28.dp))
                    step.plan?.let {
                        ReturnPlanPanel(it)
                        Spacer(Modifier.height(16.dp))
                    }
                    Note(R.drawable.ic_car, "${step.pointName}까지 경로를 안내합니다.")
                }
            }
        }
    }
}

/** '기록 중' 표시와 지금까지의 건수. 인수 뒤로 변화가 없어도 기록 중이라는 사실을 보여 준다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingLine(log: RentalLog, pickup: VehicleState?) {
    val c = ChagokTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Tag("기록 중", color = c.good, container = c.goodSoft, icon = R.drawable.ic_check_circle)
        Text(
            when {
                log.events == 0 && log.gaps == 0 -> Rental.timelineQuiet(pickup)
                log.gaps == 0 -> "대여 중 기록 ${log.events}건"
                else -> "대여 중 기록 ${log.events}건  ·  공백 ${log.gaps}건"
            },
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = c.ink2,
        )
    }
}

/** 고객을 지켜 주는 사실 한 줄. 초록 방패와 글자를 함께 쓴다. */
@Composable
internal fun ShieldNote(text: String) {
    val c = ChagokTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = c.good, modifier = Modifier.padding(top = 4.dp).size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.good)
    }
}

/** 도착 예상 배터리와 인수 때의 비교. 반납 체크와 같은 게이지를 쓴다. 인수 때보다 낮으면 고객이 미리 결정할 수 있다고 알린다. */
@Composable
private fun ReturnPlanPanel(plan: ReturnPlan) {
    EnergyGaugeView(plan.gauge, headline = plan.summary, reachedText = ReturnTrip.ENOUGH)
    if (plan.lower) {
        Spacer(Modifier.height(12.dp))
        Note(R.drawable.ic_battery, ReturnTrip.LOWER_HINT)
    }
}
