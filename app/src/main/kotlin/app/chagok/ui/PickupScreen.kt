package app.chagok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chagok.R
import app.chagok.core.inspect.describe
import app.chagok.core.inspect.display
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.Rental

/**
 * V-03 인수 기록. QR과 '폰으로 받았습니다'를 운전석 쪽에 두고, 잔량·주행거리·경고등 요약, 기록 번호, 무결성 값을 함께 보여 준다.
 * 경고등이 켜져 있으면 출발 전에 직원에게 보여 달라고 눈에 띄게 알린다. 입력 없이 3분이 지나면 QR을 내리고 대여 중 화면으로 간다('인수 기록 보기'로 돌아올 수 있다).
 */
@Composable
internal fun PickupDone(pickup: SnapshotRecord, url: String?, onDone: () -> Unit) {
    AfterIdle(onDone)
    val meta = "${time(pickup.capturedAt)}  ·  기록 번호 ${pickup.recordNo}"
    val telltales = pickup.state.telltales.orEmpty()
    val wide = LocalWide.current
    WithQr(
        url = url,
        qrShown = true,
        actions = { ActionRow { ChagokButton("폰으로 받았습니다", onDone, modifier = Modifier.widthIn(min = 420.dp)) } },
        head = {
            val c = ChagokTheme.colors
            Text("인수 기록을 남겼습니다", style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium, color = c.ink)
            if (wide) {
                Spacer(Modifier.height(12.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
                Spacer(Modifier.height(8.dp))
                IntegrityValue(pickup.hash)
            }
            if (telltales.isNotEmpty()) {
                Spacer(Modifier.height(if (wide) 24.dp else 12.dp))
                TelltaleWarning(telltales)
            }
        },
        rest = {
            if (!wide) {
                val c = ChagokTheme.colors
                Spacer(Modifier.height(16.dp))
                Text(meta, style = MaterialTheme.typography.bodyMedium.tabular, color = c.ink2)
                Spacer(Modifier.height(8.dp))
                IntegrityValue(pickup.hash)
            }
            Spacer(Modifier.height(32.dp))
            PickupFacts(pickup.state, columns = 2)
        },
    )
}

// 경고등이 켜진 채 출발하면 나중에 다툼이 된다. 지금 볼 것이라 주황으로 눈에 띄게 둔다.
@Composable
private fun TelltaleWarning(telltales: Set<Telltale>) {
    val c = ChagokTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.signalSoft, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = c.signalInk, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(Rental.PICKUP_TELLTALE_HINT, style = MaterialTheme.typography.titleLarge, color = c.signalInk)
            Text(
                "켜진 경고등 ${telltales.size}개: ${telltales.map { it.label }.sorted().joinToString()}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
            )
        }
    }
}

private val PICKUP_FACTS = listOf(
    StateItem.FUEL, StateItem.EV_SOC, StateItem.ODOMETER, StateItem.TELLTALES, StateItem.TIRES,
    StateItem.ENGINE_OIL, StateItem.BRAKE_FLUID, StateItem.WASHER_FLUID, StateItem.TRUNK, StateItem.CHARGE_PORT,
)

@Composable
private fun PickupFacts(state: VehicleState, columns: Int) {
    // 휘발유차의 배터리처럼 이 차에 없는 항목은 칸을 만들지 않는다.
    val items = PICKUP_FACTS.filter { state.display(it) != null || state.missing(it) !is Unavailable.Unsupported }
    Column {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                row.forEach { item -> Fact(state, item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 인수 기록의 한 칸. 켜진 경고등·부족한 소모품·저압 타이어·열린 트렁크와 충전구는 주황으로 적는다(고객이 인수 때부터였다는 근거). */
@Composable
private fun Fact(state: VehicleState, item: StateItem, modifier: Modifier = Modifier) {
    val c = ChagokTheme.colors
    val value = state.display(item)
    val attention = when (item) {
        StateItem.TELLTALES -> state.telltales?.isNotEmpty() == true
        StateItem.TIRES -> state.tires.values.any { it.lowPressure == true } || state.tirePressureLamp == true
        StateItem.TRUNK -> state.trunkOpen == true
        StateItem.CHARGE_PORT -> state.chargePortOpen == true
        StateItem.ENGINE_OIL -> state.engineOil == FluidLevel.LOW
        StateItem.BRAKE_FLUID -> state.brakeFluid == FluidLevel.LOW
        StateItem.WASHER_FLUID -> state.washerFluid == FluidLevel.LOW
        else -> false
    }
    val percent = when (item) {
        StateItem.FUEL -> state.fuelPercent
        StateItem.EV_SOC -> state.evSocPercent
        else -> null
    }
    // 칸마다 위에 선을 둬 표처럼 읽히게 한다.
    Column(modifier.padding(bottom = 24.dp)) {
        HorizontalDivider(color = if (attention) c.signal else c.rule, thickness = if (attention) 2.dp else 1.dp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(item.icon()),
                contentDescription = null,
                tint = if (attention) c.signalInk else c.ink2,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = c.ink2)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value ?: state.missing(item)?.describe().orEmpty(),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium).tabular,
            color = when {
                value == null -> c.ink2
                attention -> c.signalInk
                else -> c.ink
            },
        )
        if (percent != null) {
            Spacer(Modifier.height(8.dp))
            EnergyBar(after = percent.toInt())
        }
    }
}
