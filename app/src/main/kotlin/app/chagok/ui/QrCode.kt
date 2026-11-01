package app.chagok.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.round

/**
 * QR 코드. 링크가 길어(약 1,400자) 모듈이 작으므로, 모듈 경계를 정수 픽셀에 맞춰 번지지 않게 그린다.
 * 바탕(흰 판과 여백)은 부르는 쪽이 깐다. 오류 정정은 가장 낮은 L을 쓴다.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    // 긴 링크는 인코딩에 수십 ms가 걸려 화면 전환이 끊기므로 메인 스레드 밖에서 만든다.
    val matrix by produceState<BitMatrix?>(null, text) { value = withContext(Dispatchers.Default) { encode(text) } }
    Spacer(
        modifier
            .semantics { contentDescription = "같은 기록을 여는 QR 코드" }
            .drawWithCache {
                val code = matrix ?: return@drawWithCache onDrawBehind {}
                val n = code.width
                val side = floor(minOf(size.width, size.height))
                val left = floor((size.width - side) / 2)
                val top = floor((size.height - side) / 2)
                // 모듈 경계를 정수 픽셀에 맞춘다. 칸 폭이 2·3px로 섞여도 전체는 받은 크기를 채운다.
                fun edge(i: Int) = round(i * side / n)
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

private fun encode(text: String): BitMatrix {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}
