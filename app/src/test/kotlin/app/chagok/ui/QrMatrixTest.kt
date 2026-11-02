package app.chagok.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class QrMatrixTest {
    @Test
    fun `같은 링크는 다시 만들지 않고 바로 돌려준다`() {
        val made = QrMatrix.of("https://example.com/r#a")
        assertSame(made, QrMatrix.cached("https://example.com/r#a"), "다시 볼 때 빈 판을 보이지 않는다")
        assertSame(made, QrMatrix.of("https://example.com/r#a"))
    }

    @Test
    fun `다른 링크에는 앞 기록의 QR을 내주지 않는다`() {
        val first = QrMatrix.of("https://example.com/r#first")
        assertNull(QrMatrix.cached("https://example.com/r#second"))
        assertNotSame(first, QrMatrix.of("https://example.com/r#second"))
        assertNull(QrMatrix.cached("https://example.com/r#first"), "마지막 한 건만 기억한다")
    }

    @Test
    fun `여백 없이 만든다 흰 판의 여백은 화면이 깐다`() {
        val matrix = QrMatrix.of("margin")
        assertEquals(matrix.width, matrix.height)
        assertEquals(21, matrix.width, "17바이트 이하는 가장 작은 판(21모듈)이다. 여백이 들어가면 커진다.")
    }
}
