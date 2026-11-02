package app.chagok.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.rental.CheckItem
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 차 그림에서 켤 수 있는 부분. 위에서 본 그림이라 앞이 위쪽이고, 운전석(FRONT_LEFT)이 왼쪽이다. */
sealed interface MapPart {
    data class Door(val area: Area) : MapPart
    data class Window(val area: Area) : MapPart
    data class Seat(val area: Area) : MapPart
    data class Wheel(val area: Area) : MapPart
    data object Headlamps : MapPart
    data object TailLamps : MapPart
    data object CabinLight : MapPart
    data object Trunk : MapPart
    data object ChargePort : MapPart
}

/** 반납 체크 항목이 차의 어디인지. 잔량·경고등처럼 자리가 없는 항목은 null */
fun CheckItem.mapPart(): MapPart? = when (this) {
    is CheckItem.WindowOpen -> MapPart.Window(area)
    is CheckItem.DoorOpen -> MapPart.Door(area)
    is CheckItem.ClosureOpen -> when (item) {
        StateItem.TRUNK -> MapPart.Trunk
        StateItem.CHARGE_PORT -> MapPart.ChargePort
        else -> null
    }
    is CheckItem.SeatDetected -> MapPart.Seat(area)
    is CheckItem.LightOn -> when (item) {
        StateItem.HEADLIGHTS -> MapPart.Headlamps
        StateItem.TAIL_LAMP -> MapPart.TailLamps
        StateItem.CABIN_LIGHT -> MapPart.CabinLight
        else -> null
    }
    is CheckItem.Unreadable, is CheckItem.EnergyDrop, is CheckItem.NewTelltales, is CheckItem.TelltalesUnverified -> null
}

private val SIDES = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT, Area.REAR_LEFT, Area.REAR_RIGHT)

private val PARTS: List<MapPart> = SIDES.map { MapPart.Door(it) } + SIDES.map { MapPart.Window(it) } +
    Area.entries.map { MapPart.Seat(it) } + SIDES.map { MapPart.Wheel(it) } +
    listOf(MapPart.Headlamps, MapPart.TailLamps, MapPart.CabinLight, MapPart.Trunk, MapPart.ChargePort)

// 그림 단위: 차 길이가 1이다. x는 차 가운데가 0, y는 앞 끝이 0이다. 문이 열릴 자리와 전조등 빛이 뻗을 자리를 남긴다.
private const val SPAN_X = 0.78f
private const val SPAN_Y = 1.12f
private const val AHEAD = 0.08f

/**
 * 위에서 본 차(지붕을 걷어 낸 그림). [lit]에 든 부분은 주황으로 켜고, 빠지면 천천히 끈다. 열린 문은 바깥으로 벌어진다.
 * [scanning]이면 앞에서 뒤로 훑는 띠를 그린다(차 상태를 읽는 동안).
 */
@Composable
fun VehicleMap(
    lit: Set<MapPart>,
    modifier: Modifier = Modifier,
    scanning: Boolean = false,
    description: String = "차 그림",
) {
    val c = ChagokTheme.colors
    // 켜질 때는 빠르게, 꺼질 때는 고친 결과가 눈에 남도록 조금 천천히
    val levels: Map<MapPart, State<Float>> = PARTS.associateWith { part ->
        val on = part in lit
        animateFloatAsState(
            targetValue = if (on) 1f else 0f,
            animationSpec = tween(if (on) 360 else 640, easing = FastOutSlowInEasing),
            label = "part",
        )
    }
    val sweep: State<Float>? = if (scanning) {
        rememberInfiniteTransition(label = "scan")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sweep")
    } else {
        null
    }

    // 받은 크기 안에서 비율을 지켜 가운데에 그린다(Geo). 넓은 창과 좁은 창 모두 같은 그림이다.
    Canvas(modifier.semantics { contentDescription = description }) {
        val g = Geo(size)
        val level = { part: MapPart -> levels.getValue(part).value }
        val body = g.body()

        // 바퀴는 차체 밑에 그려 옆으로 조금만 보이게 한다.
        SIDES.forEach { area -> wheel(g, area, level(MapPart.Wheel(area)), c) }
        bodyFill(body, c)
        mirrors(g, c)
        cabin(g, c, level(MapPart.CabinLight))
        Area.entries.forEach { area -> seat(g, area, level(MapPart.Seat(area)), c) }
        steeringWheel(g, c)
        glass(g, c)
        SIDES.forEach { area -> sideWindow(g, area, level(MapPart.Window(area)), c) }
        doorSeams(g, c)
        headlamps(g, level(MapPart.Headlamps), c)
        tailLamps(g, level(MapPart.TailLamps), c)
        trunkLid(g, level(MapPart.Trunk), c)
        chargePort(g, level(MapPart.ChargePort), c)
        drawPath(body, c.map.line, style = Stroke(width = max(2.dp.toPx(), g.d(0.004f))))
        SIDES.forEach { area -> openDoor(g, area, level(MapPart.Door(area)), c) }
        sweep?.let { scan(g, body, it.value, c) }
    }
}

private class Geo(size: Size) {
    val s = min(size.width / SPAN_X, size.height / SPAN_Y)
    private val cx = size.width / 2
    private val oy = (size.height - SPAN_Y * s) / 2 + AHEAD * s

    fun x(u: Float) = cx + u * s
    fun y(v: Float) = oy + v * s
    fun o(u: Float, v: Float) = Offset(x(u), y(v))
    fun d(u: Float) = u * s
    fun rect(u0: Float, v0: Float, u1: Float, v1: Float) = Rect(min(x(u0), x(u1)), y(v0), max(x(u0), x(u1)), y(v1))

    fun body() = Path().apply {
        moveTo(x(-0.17f), y(0.006f))
        quadraticTo(x(0f), y(-0.010f), x(0.17f), y(0.006f))
        cubicTo(x(0.214f), y(0.012f), x(0.229f), y(0.045f), x(0.230f), y(0.10f))
        lineTo(x(0.233f), y(0.32f))
        lineTo(x(0.233f), y(0.85f))
        cubicTo(x(0.232f), y(0.952f), x(0.214f), y(0.986f), x(0.17f), y(0.993f))
        quadraticTo(x(0f), y(1.004f), x(-0.17f), y(0.993f))
        cubicTo(x(-0.214f), y(0.986f), x(-0.232f), y(0.952f), x(-0.233f), y(0.85f))
        lineTo(x(-0.233f), y(0.32f))
        lineTo(x(-0.230f), y(0.10f))
        cubicTo(x(-0.229f), y(0.045f), x(-0.214f), y(0.012f), x(-0.17f), y(0.006f))
        close()
    }
}

private val Area.sign: Float
    get() = when (this) {
        Area.FRONT_LEFT, Area.REAR_LEFT -> -1f
        Area.FRONT_RIGHT, Area.REAR_RIGHT -> 1f
        Area.REAR_CENTER -> 0f
    }

private val Area.isFront: Boolean get() = this == Area.FRONT_LEFT || this == Area.FRONT_RIGHT

// 문은 앞 0.338~0.552, 뒤 0.552~0.766. 창문은 그 안쪽 띠다.
private fun doorSpan(area: Area) = if (area.isFront) 0.338f to 0.552f else 0.552f to 0.766f
private fun windowSpan(area: Area) = if (area.isFront) 0.348f to 0.544f else 0.560f to 0.754f

private fun DrawScope.bodyFill(body: Path, c: ChagokColors) {
    // 낮에는 흰 차체가 바탕에서 뜨도록 Pleos 그림자 1단계(y 6, blur 24)를 쓴다.
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = c.map.body.toArgb()
            if (c.map.shadow.alpha > 0f) setShadowLayer(24.dp.toPx(), 0f, 6.dp.toPx(), c.map.shadow.toArgb())
        }
        canvas.nativeCanvas.drawPath(body.asAndroidPath(), paint)
    }
}

private fun DrawScope.wheel(g: Geo, area: Area, t: Float, c: ChagokColors) {
    val s = area.sign
    val (v0, v1) = if (area.isFront) 0.118f to 0.212f else 0.790f to 0.884f
    val r = g.rect(s * 0.204f, v0, s * 0.249f, v1)
    drawRoundRect(lerp(c.map.wheel, c.signal, t), r.topLeft, r.size, CornerRadius(g.d(0.012f)))
    // 밤에는 바퀴가 바탕에 묻히므로 윤곽을 둔다.
    drawRoundRect(lerp(c.map.line, c.signalInk, t), r.topLeft, r.size, CornerRadius(g.d(0.012f)), style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.mirrors(g: Geo, c: ChagokColors) {
    listOf(-1f, 1f).forEach { s ->
        val r = g.rect(s * 0.226f, 0.296f, s * 0.266f, 0.330f)
        drawRoundRect(c.map.body, r.topLeft, r.size, CornerRadius(g.d(0.012f)))
        drawRoundRect(c.map.line, r.topLeft, r.size, CornerRadius(g.d(0.012f)), style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.cabin(g: Geo, c: ChagokColors, light: Float) {
    val r = g.rect(-0.172f, 0.338f, 0.172f, 0.762f)
    drawRect(c.map.cabin, r.topLeft, r.size)
    val center = g.o(0f, 0.55f)
    if (light > 0f) {
        clipRect(r.left, r.top, r.right, r.bottom) {
            drawCircle(
                Brush.radialGradient(listOf(c.signal.copy(alpha = 0.42f * light), Color.Transparent), center, g.d(0.17f)),
                radius = g.d(0.17f),
                center = center,
            )
        }
    }
    drawCircle(lerp(c.map.lamp, c.signal, light), g.d(0.013f), center)
}

private fun DrawScope.seat(g: Geo, area: Area, t: Float, c: ChagokColors) {
    val center = when (area) {
        Area.FRONT_LEFT -> -0.088f
        Area.FRONT_RIGHT -> 0.088f
        Area.REAR_LEFT -> -0.106f
        Area.REAR_CENTER -> 0f
        Area.REAR_RIGHT -> 0.106f
    }
    val front = area.isFront
    val half = if (front) 0.052f else 0.046f
    val (c0, c1) = if (front) 0.392f to 0.480f else 0.592f to 0.668f
    val (b0, b1) = if (front) 0.474f to 0.508f else 0.662f to 0.694f
    val fill = lerp(c.map.seat, c.signal, t)
    val edge = lerp(c.map.line.copy(alpha = 0.55f), c.signalInk, t)
    val cushion = g.rect(center - half, c0, center + half, c1)
    val back = g.rect(center - half - 0.004f, b0, center + half + 0.004f, b1)
    listOf(cushion to 0.016f, back to 0.012f).forEach { (r, radius) ->
        drawRoundRect(fill, r.topLeft, r.size, CornerRadius(g.d(radius)))
        drawRoundRect(edge, r.topLeft, r.size, CornerRadius(g.d(radius)), style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.steeringWheel(g: Geo, c: ChagokColors) {
    val r = g.rect(-0.133f, 0.351f, -0.043f, 0.375f)
    drawOval(c.map.line, r.topLeft, r.size, style = Stroke(max(2.dp.toPx(), g.d(0.006f))))
}

private fun DrawScope.glass(g: Geo, c: ChagokColors) {
    val windshield = Path().apply {
        moveTo(g.x(-0.196f), g.y(0.262f))
        quadraticTo(g.x(0f), g.y(0.238f), g.x(0.196f), g.y(0.262f))
        lineTo(g.x(0.172f), g.y(0.338f))
        quadraticTo(g.x(0f), g.y(0.326f), g.x(-0.172f), g.y(0.338f))
        close()
    }
    val rear = Path().apply {
        moveTo(g.x(-0.172f), g.y(0.762f))
        quadraticTo(g.x(0f), g.y(0.772f), g.x(0.172f), g.y(0.762f))
        lineTo(g.x(0.190f), g.y(0.830f))
        quadraticTo(g.x(0f), g.y(0.848f), g.x(-0.190f), g.y(0.830f))
        close()
    }
    listOf(windshield, rear).forEach { path ->
        drawPath(path, c.map.glass)
        drawPath(path, c.map.line, style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.sideWindow(g: Geo, area: Area, t: Float, c: ChagokColors) {
    val s = area.sign
    val (v0, v1) = windowSpan(area)
    val r = g.rect(s * 0.174f, v0, s * 0.203f, v1)
    val radius = CornerRadius(g.d(0.008f))
    if (t > 0f) {
        val halo = RoundRect(r.inflate(g.d(0.012f)), CornerRadius(g.d(0.018f)))
        drawPath(Path().apply { addRoundRect(halo) }, c.signal.copy(alpha = 0.22f * t))
    }
    drawRoundRect(lerp(c.map.glass, c.signal, t), r.topLeft, r.size, radius)
    drawRoundRect(lerp(c.map.line, c.signalInk, t), r.topLeft, r.size, radius, style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.doorSeams(g: Geo, c: ChagokColors) {
    listOf(-1f, 1f).forEach { s ->
        listOf(0.338f, 0.552f, 0.766f).forEach { v ->
            drawLine(c.map.line, g.o(s * 0.203f, v), g.o(s * 0.233f, v), strokeWidth = 1.5.dp.toPx())
        }
    }
}

private fun DrawScope.headlamps(g: Geo, t: Float, c: ChagokColors) {
    listOf(-1f, 1f).forEach { s ->
        if (t > 0f) {
            val beam = Path().apply {
                moveTo(g.x(s * 0.206f), g.y(0.046f))
                lineTo(g.x(s * 0.108f), g.y(0.013f))
                lineTo(g.x(s * 0.040f), g.y(-0.078f))
                lineTo(g.x(s * 0.300f), g.y(-0.078f))
                close()
            }
            drawPath(
                beam,
                Brush.verticalGradient(
                    listOf(Color.Transparent, c.signal.copy(alpha = 0.34f * t)),
                    startY = g.y(-0.078f),
                    endY = g.y(0.03f),
                ),
            )
        }
        val lamp = Path().apply {
            moveTo(g.x(s * 0.206f), g.y(0.046f))
            quadraticTo(g.x(s * 0.180f), g.y(0.018f), g.x(s * 0.108f), g.y(0.013f))
        }
        drawPath(lamp, lerp(c.map.lamp, c.signal, t), style = Stroke(g.d(0.016f), cap = StrokeCap.Round))
    }
}

private fun DrawScope.tailLamps(g: Geo, t: Float, c: ChagokColors) {
    listOf(-1f, 1f).forEach { s ->
        val lamp = Path().apply {
            moveTo(g.x(s * 0.206f), g.y(0.958f))
            quadraticTo(g.x(s * 0.185f), g.y(0.984f), g.x(s * 0.112f), g.y(0.988f))
        }
        if (t > 0f) {
            drawPath(lamp, c.signal.copy(alpha = 0.18f * t), style = Stroke(g.d(0.06f), cap = StrokeCap.Round))
            drawPath(lamp, c.signal.copy(alpha = 0.30f * t), style = Stroke(g.d(0.032f), cap = StrokeCap.Round))
        }
        drawPath(lamp, lerp(c.map.lamp, c.signal, t), style = Stroke(g.d(0.014f), cap = StrokeCap.Round))
    }
}

// 뒤 유리 뒤쪽 덮개를 주황으로 켜고 뒤로 조금 밀어 열린 것처럼 보인다.
private fun DrawScope.trunkLid(g: Geo, t: Float, c: ChagokColors) {
    if (t < 0.005f) return
    val shift = 0.03f * t
    val lid = Path().apply {
        moveTo(g.x(-0.190f), g.y(0.838f + shift))
        quadraticTo(g.x(0f), g.y(0.856f + shift), g.x(0.190f), g.y(0.838f + shift))
        lineTo(g.x(0.176f), g.y(0.968f + shift))
        quadraticTo(g.x(0f), g.y(0.984f + shift), g.x(-0.176f), g.y(0.968f + shift))
        close()
    }
    drawPath(lid, c.signal.copy(alpha = 0.30f * t))
    drawPath(lid, lerp(c.map.line, c.signal, t), style = Stroke(max(2.dp.toPx(), g.d(0.006f))))
}

// 충전구는 뒷바퀴 뒤 오른쪽에 둔다. 열렸을 때만 그린다(휘발유차에는 없다).
private fun DrawScope.chargePort(g: Geo, t: Float, c: ChagokColors) {
    if (t < 0.005f) return
    val r = g.rect(0.196f, 0.902f, 0.236f, 0.948f)
    val halo = RoundRect(r.inflate(g.d(0.012f)), CornerRadius(g.d(0.018f)))
    drawPath(Path().apply { addRoundRect(halo) }, c.signal.copy(alpha = 0.26f * t))
    drawRoundRect(lerp(c.map.body, c.signal, t), r.topLeft, r.size, CornerRadius(g.d(0.008f)))
    drawRoundRect(lerp(c.map.line, c.signalInk, t), r.topLeft, r.size, CornerRadius(g.d(0.008f)), style = Stroke(1.5.dp.toPx()))
}

// 문이 있던 자리를 비우고 문짝을 앞쪽 경첩을 축으로 바깥으로 벌린다(t=1일 때 42도).
private fun DrawScope.openDoor(g: Geo, area: Area, t: Float, c: ChagokColors) {
    if (t < 0.005f) return
    val s = area.sign
    val (v0, v1) = doorSpan(area)
    val gap = g.rect(s * 0.203f, v0, s * 0.234f, v1)
    drawRect(lerp(c.map.body, c.signalSoft, t), gap.topLeft, gap.size)
    drawRect(c.signal.copy(alpha = t), gap.topLeft, gap.size, style = Stroke(2.dp.toPx()))
    val angle = Math.toRadians(42.0 * t)
    val hinge = g.o(s * 0.222f, v0)
    val length = g.d(v1 - v0)
    val end = Offset(hinge.x + s * length * sin(angle).toFloat(), hinge.y + length * cos(angle).toFloat())
    drawLine(c.signal.copy(alpha = 0.25f * t), hinge, end, strokeWidth = g.d(0.05f), cap = StrokeCap.Round)
    drawLine(lerp(c.map.line, c.signal, t), hinge, end, strokeWidth = g.d(0.024f), cap = StrokeCap.Round)
}

// 앞에서 뒤로 내려가는 선과 그 뒤에 남는 옅은 빛. 차체 밖으로는 그리지 않는다. 읽는 중은 '지금 볼 것'이 아니라서 주황을 쓰지 않는다.
private fun DrawScope.scan(g: Geo, body: Path, progress: Float, c: ChagokColors) {
    val v = -0.04f + progress * 1.08f
    clipPath(body) {
        val top = g.y(v - 0.16f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c.ink2.copy(alpha = 0.26f)), startY = top, endY = g.y(v)),
            topLeft = Offset(0f, top),
            size = Size(size.width, g.d(0.16f)),
        )
        drawLine(c.ink2, Offset(0f, g.y(v)), Offset(size.width, g.y(v)), strokeWidth = 3.dp.toPx())
    }
}
