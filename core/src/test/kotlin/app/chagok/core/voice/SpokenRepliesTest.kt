package app.chagok.core.voice

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpokenRepliesTest {
    @Test
    fun `뜻이 분명한 답만 네로 본다`() {
        listOf("네", "네.", " 네 ", "예", "내", "네 남겨주세요", "내 남겨주세요", "남겨 주세요", "기록 남겨 줘").forEach {
            assertEquals(SpokenReply.YES, SpokenReplies.parse(it), it)
        }
    }

    @Test
    fun `아니요와 나중에는 아니요로 본다`() {
        listOf("아니요", "아니오", "아뇨.", "아니", "나중에", "남기지 마세요").forEach {
            assertEquals(SpokenReply.NO, SpokenReplies.parse(it), it)
        }
    }

    @Test
    fun `동승자의 말이나 맞장구로는 기록이 확정되지 않는다`() {
        listOf(
            "응", "어", "그래", "좋아", "네 그런데 말이야", "네 알겠어요", "네 맞아요", "내 거야", "내가 할게", "오늘 날씨가 정말 좋네요",
            "남겨 두세요", "네 아니요", "아니 네", "", "   ", "4",
        ).forEach {
            assertEquals(SpokenReply.UNCLEAR, SpokenReplies.parse(it), it)
        }
        assertEquals(SpokenReply.UNCLEAR, SpokenReplies.parse(null))
    }

    @Test
    fun `반납 기록을 묻는 안내는 그 질문으로 끝난다`() {
        val window = CheckItem.WindowOpen(Area.FRONT_RIGHT)
        val cabin = CheckItem.LightOn(StateItem.CABIN_LIGHT)
        assertTrue(Voice.returnCheck(emptyList()).endsWith(Voice.RECORD_QUESTION))
        assertTrue(Voice.resolved(window, emptyList())!!.endsWith(Voice.RECORD_QUESTION))
        assertTrue(!Voice.resolved(window, listOf(cabin))!!.endsWith(Voice.RECORD_QUESTION), "남은 것이 있으면 묻지 않는다")
        assertTrue(!Voice.returnCheck(listOf(window)).endsWith(Voice.RECORD_QUESTION))
        assertTrue(!Voice.returnCheck(emptyList(), askToRecord = false).endsWith(Voice.RECORD_QUESTION))
    }
}
