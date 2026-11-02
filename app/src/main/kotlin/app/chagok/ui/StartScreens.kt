package app.chagok.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.core.rental.Rental

/** V-01 대기. 왜 기록하는지와 차량 번호만 보여 준다. 값 출처는 직원 화면에 있다. */
@Composable
internal fun Idle(vehicle: String, parked: Boolean, onStart: () -> Unit) {
    val c = ChagokTheme.colors
    WithMap(
        map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), description = "차 그림") },
        centered = true,
        lead = {
            Text(Rental.IDLE_TITLE, style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text("외관은 사진이, 기능은 차가 기록합니다.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
            if (vehicle.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Tag("차량 $vehicle", color = c.ink2, container = c.sunken, icon = R.drawable.ic_car)
            }
        },
        actions = {
            if (!parked) ParkFirst("정차하면 시작할 수 있습니다")
            ChagokButton("인수 기록 시작", onStart, enabled = parked, modifier = Modifier.widthIn(min = 420.dp))
        },
    )
}

/**
 * V-02 고지. 문구는 법률 검토 전 초안이다(요구사항 SER-03). 왜 기록하는지와 거절하면 어떻게 되는지를 함께 적는다.
 * 거절도 시각과 차량만 기록으로 남고, 직원 화면에 보인다.
 */
@Composable
internal fun Notice(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = ChagokTheme.colors
    val recorded = listOf(
        R.drawable.ic_fuel to "잔량(연료 또는 배터리), 주행거리",
        R.drawable.ic_warning to "경고등, 소모품 상태",
        R.drawable.ic_tire to "타이어 공기압 경고, 안전장치 상태",
        R.drawable.ic_door to "문·창문·트렁크·라이트 등 열리고 켜지는 곳",
    )
    val notRecorded = listOf(
        R.drawable.ic_location_off to "위치",
        R.drawable.ic_person_off to "이름·연락처",
        R.drawable.ic_mic_off to "음성 원문",
        R.drawable.ic_history_off to "운전 습관, 문 열림 이력",
    )
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().fadeBottom(scroll).verticalScroll(scroll)) {
            Text("차량 상태를 기록할까요?", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text(Rental.NOTICE_WHY, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text("${Rental.NOTICE_KEEP} ${Rental.NOTICE_DURING_RENTAL}", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
            Spacer(Modifier.height(32.dp))
            if (LocalWide.current) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    Promise("기록하는 것", R.drawable.ic_check_circle, recorded, Modifier.weight(1f), Rental.NOTICE_ONLY_READABLE)
                    Promise("기록하지 않는 것", R.drawable.ic_block, notRecorded, Modifier.weight(1f))
                }
            } else {
                Promise("기록하는 것", R.drawable.ic_check_circle, recorded, subtitle = Rental.NOTICE_ONLY_READABLE)
                Spacer(Modifier.height(32.dp))
                Promise("기록하지 않는 것", R.drawable.ic_block, notRecorded)
            }
        }
        Spacer(Modifier.height(20.dp))
        Note(R.drawable.ic_info, Rental.NOTICE_DECLINE)
        Spacer(Modifier.height(16.dp))
        ActionRow {
            ChagokButton("확인하고 기록 시작", onAccept, modifier = Modifier.widthIn(min = 420.dp))
            ChagokButton("기록하지 않음", onDecline, kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun Promise(title: String, icon: Int, items: List<Pair<Int, String>>, modifier: Modifier = Modifier, subtitle: String? = null) {
    val c = ChagokTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = c.ink, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            subtitle?.let {
                Spacer(Modifier.width(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink2)
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = c.rule)
        items.forEach { (itemIcon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                Icon(painterResource(itemIcon), contentDescription = null, tint = c.ink2, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(20.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
            HorizontalDivider(color = c.rule)
        }
    }
}

/** 상태를 읽는 동안. 0.25초를 넘기는 응답은 진행 중임을 보여 준다(Pleos 디자인 가이드). */
@Composable
internal fun Reading(title: String, detail: String) {
    val c = ChagokTheme.colors
    WithMap(
        map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), scanning = true, description = "차 상태를 읽는 중") },
        centered = true,
        lead = {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(16.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        },
    )
}

/**
 * 저장된 기록이 무결성 값과 맞지 않을 때. 이어 가지 않고 직원에게 넘긴다. 직원 화면의 무효 처리로만 풀린다.
 * 고객이 할 수 있는 일은 직원을 부르는 것뿐이고, 직원이 찾기 쉽게 직원 화면을 여는 버튼을 둔다(무효는 PIN을 받는다).
 */
@Composable
internal fun Damaged(message: String, onStaff: () -> Unit) {
    val c = ChagokTheme.colors
    WithMap(
        map = { VehicleMap(emptySet(), Modifier.fillMaxSize(), description = "차 그림") },
        centered = true,
        lead = {
            IconBadge(R.drawable.ic_error, c.bad, c.badSoft, size = 88.dp)
            Spacer(Modifier.height(24.dp))
            Text("이 대여를 이어 갈 수 없습니다", style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        },
        actions = { ChagokButton("직원 화면 열기", onStaff, kind = ButtonKind.Secondary) },
    )
}

/** 반납을 마친 뒤 저장된 리포트를 읽지 못했을 때. 닫아도 저장된 파일은 그대로 남는다. */
@Composable
internal fun ReportUnreadable(onClose: () -> Unit) {
    Ending(R.drawable.ic_error, isError = true, title = "저장된 리포트를 읽지 못했습니다", detail = "직원에게 알려 주세요.", onClose)
}

/** 무효. 리포트를 만들지 않는다. */
@Composable
internal fun Voided(onClose: () -> Unit) {
    Ending(R.drawable.ic_block, isError = false, title = "무효 처리했습니다", detail = "이 대여는 리포트를 만들지 않습니다.", onClose)
}

@Composable
private fun Ending(icon: Int, isError: Boolean, title: String, detail: String, onClose: () -> Unit) {
    val c = ChagokTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            IconBadge(icon, if (isError) c.bad else c.ink2, if (isError) c.badSoft else c.sunken, size = 88.dp)
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
        }
        Spacer(Modifier.height(20.dp))
        ActionRow { ChagokButton("닫기", onClose, modifier = Modifier.widthIn(min = 420.dp)) }
    }
}
