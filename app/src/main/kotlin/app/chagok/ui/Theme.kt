package app.chagok.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 차곡의 색. 기획 문서와 같은 주황 신호색(signal)을 중심에 두고, Pleos 시스템의 낮/밤 테마를 따라 두 벌을 쓴다.
 * 주황은 "지금 볼 것"에만 쓴다. 확인이 끝난 것은 good, 읽지 못한 것은 ink3로 그린다.
 */
@Immutable
data class ChagokColors(
    val dark: Boolean,
    /** 창 바탕 */
    val paper: Color,
    /** 바탕보다 한 단 올라온 면. 직원 화면 */
    val raised: Color,
    /** 바탕보다 한 단 들어간 면. 자막 띠, 보조 버튼, 칩 */
    val sunken: Color,
    val rule: Color,
    val ink: Color,
    val ink2: Color,
    /** 비활성과 장식. 작은 글자에는 쓰지 않는다(대비 3:1 미만). */
    val ink3: Color,
    /** 주 버튼 채움과 차 그림의 강조 */
    val signal: Color,
    val onSignal: Color,
    /** 바탕 위의 주황 글자·아이콘. 바탕 대비 4.5:1 이상 */
    val signalInk: Color,
    val signalSoft: Color,
    val good: Color,
    val goodSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val map: MapColors,
)

/** 차 그림(VehicleMap)의 중립색 */
@Immutable
data class MapColors(
    val body: Color,
    val line: Color,
    val glass: Color,
    val cabin: Color,
    val seat: Color,
    val wheel: Color,
    val lamp: Color,
    val shadow: Color,
)

// 낮: Pleos 시스템 UI의 밝은 회색 바탕에 맞춘다. 주황 글자는 바탕 위에서 4.8:1이 되도록 한 단 어둡게 쓴다.
private val Day = ChagokColors(
    dark = false,
    paper = Color(0xFFF2F4F5),
    raised = Color(0xFFFFFFFF),
    sunken = Color(0xFFE5E9EB),
    rule = Color(0xFFD5DBDE),
    ink = Color(0xFF14191C),
    ink2 = Color(0xFF55616A),
    ink3 = Color(0xFF8B969D),
    signal = Color(0xFFEC9846),
    onSignal = Color(0xFF1C1206),
    signalInk = Color(0xFFA4570F),
    signalSoft = Color(0xFFFCEAD6),
    good = Color(0xFF1E7A4C),
    goodSoft = Color(0xFFDCF0E4),
    bad = Color(0xFFB3261E),
    badSoft = Color(0xFFF9DEDC),
    map = MapColors(
        body = Color(0xFFFFFFFF),
        line = Color(0xFF8D99A1),
        glass = Color(0xFFD3DCE1),
        cabin = Color(0xFFF0F3F5),
        seat = Color(0xFFDDE3E7),
        wheel = Color(0xFF394246),
        lamp = Color(0xFFC2CBD0),
        shadow = Color(0x24000000),
    ),
)

// 밤: 기획 문서의 어두운 바탕 그대로다.
private val Night = ChagokColors(
    dark = true,
    paper = Color(0xFF121719),
    raised = Color(0xFF1B2225),
    sunken = Color(0xFF20292D),
    rule = Color(0xFF2C353B),
    ink = Color(0xFFE3E7E9),
    ink2 = Color(0xFF9BA6AE),
    ink3 = Color(0xFF66727A),
    signal = Color(0xFFEC9846),
    onSignal = Color(0xFF1C1206),
    signalInk = Color(0xFFEFA35C),
    signalSoft = Color(0xFF3A2919),
    good = Color(0xFF74CFA0),
    goodSoft = Color(0xFF173226),
    bad = Color(0xFFF2B8B5),
    badSoft = Color(0xFF3D2020),
    map = MapColors(
        body = Color(0xFF1D2528),
        line = Color(0xFF5E6B73),
        glass = Color(0xFF2B373D),
        cabin = Color(0xFF151B1E),
        seat = Color(0xFF2B3539),
        wheel = Color(0xFF07090A),
        lamp = Color(0xFF3B474D),
        shadow = Color(0x00000000),
    ),
)

private val LocalChagokColors = staticCompositionLocalOf { Day }

// Pleos 시스템 글꼴(Asta Sans)이 sans-serif 자리에 들어 있어 따로 넣지 않는다.
// 시스템 설정이 굵기를 한 단 무겁게 잡아(600 → Bold, 700 → ExtraBold) 제목도 SemiBold까지만 쓴다.
private val Strong = FontWeight.SemiBold

// 운전석에서 팔을 뻗어 읽는 거리라 휴대폰보다 두 단 크게 잡는다. 가장 작은 글자도 22sp다(요구사항 QUR-03).
private val type = Typography(
    displayMedium = TextStyle(fontSize = 76.sp, lineHeight = 84.sp, fontWeight = Strong),
    headlineLarge = TextStyle(fontSize = 52.sp, lineHeight = 66.sp, fontWeight = Strong),
    headlineMedium = TextStyle(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = Strong),
    headlineSmall = TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = Strong),
    titleLarge = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = Strong),
    bodyLarge = TextStyle(fontSize = 30.sp, lineHeight = 42.sp),
    bodyMedium = TextStyle(fontSize = 26.sp, lineHeight = 38.sp),
    bodySmall = TextStyle(fontSize = 24.sp, lineHeight = 34.sp),
    labelLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = Strong),
    labelMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** 숫자 자리가 흔들리지 않게 고정폭 숫자를 쓴다. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

private fun ChagokColors.material(): ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = signal,
    onPrimary = onSignal,
    primaryContainer = signalSoft,
    onPrimaryContainer = signalInk,
    secondary = ink2,
    onSecondary = paper,
    secondaryContainer = sunken,
    onSecondaryContainer = ink,
    background = paper,
    onBackground = ink,
    surface = paper,
    onSurface = ink,
    surfaceVariant = sunken,
    onSurfaceVariant = ink2,
    surfaceContainerLowest = raised,
    surfaceContainerLow = raised,
    surfaceContainer = raised,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = sunken,
    outline = rule,
    outlineVariant = rule,
    error = bad,
    onError = paper,
    errorContainer = badSoft,
    onErrorContainer = bad,
)

/** 시스템이 밤 모드면 어두운 색을 쓴다. 차가 해 질 녘에 바꾸면 화면도 따라 바뀐다. */
@Composable
fun ChagokTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) Night else Day
    CompositionLocalProvider(LocalChagokColors provides colors) {
        MaterialTheme(colorScheme = colors.material(), typography = type, shapes = shapes, content = content)
    }
}

object ChagokTheme {
    val colors: ChagokColors
        @Composable @ReadOnlyComposable get() = LocalChagokColors.current
}
