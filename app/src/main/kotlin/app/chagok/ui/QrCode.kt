package app.chagok.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * QR 코드. 링크가 길어(약 1,400자) 모듈이 작으므로, 모듈 하나를 정수 픽셀로 맞춰 번지지 않게 그린다. 받은 크기에 모자라면 남는 자리는 여백이 된다.
 * 바탕(흰 판과 여백)은 부르는 쪽이 깐다. 오류 정정은 가장 낮은 L을 쓴다.
 * 처음 만드는 동안에는 진행 표시를 보여 준다(0.25초를 넘는 응답, Pleos 디자인 가이드). 빈 흰 판만 보이면 고장으로 보인다.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    // 긴 링크는 인코딩에 1초 안팎 걸린다(2026.11.02 에뮬레이터 디버그 빌드). 메인 스레드 밖에서 만들고, 다시 볼 때와 창 크기가 바뀔 때는 기억한 값을 바로 쓴다.
    val matrix by produceState(QrMatrix.cached(text), text) {
        value = QrMatrix.cached(text) ?: withContext(Dispatchers.Default) { QrMatrix.of(text) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (matrix == null) {
            // 흰 판 위라 테마와 상관없이 어두운 색을 쓴다.
            CircularProgressIndicator(color = Color(0xFF55616A), strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
        }
        Spacer(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "같은 기록을 여는 QR 코드" }
                .drawWithCache {
                    val code = matrix ?: return@drawWithCache onDrawBehind {}
                    val n = code.width
                    val module = floor(minOf(size.width, size.height) / n).coerceAtLeast(1f)
                    val left = floor((size.width - module * n) / 2)
                    val top = floor((size.height - module * n) / 2)
                    fun edge(i: Int) = i * module
                    // 가로로 이어진 검은 모듈을 한 사각형으로 묶어 경로 하나로 그린다.
                    val path = Path()
                    for (y in 0 until n) {
                        var x = 0
                        while (x < n) {
                            if (!code[x, y]) { x++; continue }
                            val start = x
                            while (x < n && code[x, y]) x++
                            path.addRect(Rect(left + edge(start), top + edge(y), left + edge(x), top + edge(y + 1)))
                        }
                    }
                    onDrawBehind { drawPath(path, Color.Black) }
                },
        )
    }
}

/** 인코딩 결과를 마지막 한 건만 기억한다. 프로세스 메모리에만 있고 저장하지 않는다. */
internal object QrMatrix {
    @Volatile
    private var last: Pair<String, BitMatrix>? = null

    fun cached(text: String): BitMatrix? = last?.takeIf { it.first == text }?.second

    fun of(text: String): BitMatrix = cached(text) ?: encode(text).also { last = text to it }

    private fun encode(text: String): BitMatrix {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
}
