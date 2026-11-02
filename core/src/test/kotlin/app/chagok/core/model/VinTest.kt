package app.chagok.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VinTest {
    @Test
    fun `형식이 맞는 차대번호만 받는다`() {
        assertEquals("KMHC851HFNU123456", Vin.parse("KMHC851HFNU123456"))
        assertEquals("KMHC851HFNU123456", Vin.parse(" kmhc851hfnu123456 "))
        assertEquals("KMHC851HFNU123456", Vin.parse("\"KMHC851HFNU123456\""))
    }

    @Test
    fun `에뮬레이터가 주는 값과 빈 값은 차대번호가 아니다`() {
        assertEquals(null, Vin.parse("\"invalid\""))
        assertEquals(null, Vin.parse("invalid"))
        assertEquals(null, Vin.parse(""))
        assertEquals(null, Vin.parse(null))
        assertEquals(null, Vin.parse("KMHC851HFNU12345"), "16자리")
        assertEquals(null, Vin.parse("KMHC851HFNU12345O"), "O는 쓰지 않는다")
    }

    @Test
    fun `화면에는 끝 6자리만 보이고 업체 번호는 그대로 보인다`() {
        assertEquals("…123456", Vin.label("KMHC851HFNU123456"))
        assertEquals("V-0007", Vin.label("V-0007"))
    }
}
