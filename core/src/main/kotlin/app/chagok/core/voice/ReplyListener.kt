package app.chagok.core.voice

import kotlin.time.Duration

/**
 * '반납 기록을 남길까요?'의 답을 듣는 자리(기능 정의서 F-VOI-03). 언제 들을지는 앱의 컨트롤러가 정하고, 구현은 들은 문장만 돌려준다.
 * 들은 문장은 판단이 끝나면 버린다. 저장하지 않고 로그에도 남기지 않는다.
 */
interface ReplyListener {
    /** 첫 답이 늦지 않게 미리 띄운다. 마이크는 켜지 않는다. 대여가 열릴 때 부른다. */
    fun warmUp()

    /** [within] 안에 끝난 문장 하나를 돌려준다. 못 들었으면 null이다. 코루틴을 취소하면 바로 듣기를 멈춘다. */
    suspend fun listen(within: Duration): String?

    /** 내린다. 대여가 끝나면 부른다. */
    fun release()
}

enum class SpokenReply { YES, NO, UNCLEAR }

/**
 * 들은 문장을 답으로 바꾼다. 차 안에는 동승자가 있고 반납 기록은 되돌릴 수 없으므로 문장 전체가 정해진 답과 같을 때만 인정한다.
 * '응', '그래'처럼 대화에서 흔한 맞장구와 '네, 그런데…'처럼 뒤에 말이 더 붙은 것은 답으로 보지 않는다. 그래도 버튼은 그대로 있다.
 */
object SpokenReplies {
    // 인식기는 '네'를 '내'로 적기도 한다(2026.11.02 에뮬레이터: '네 남겨 주세요' → '내 남겨주세요').
    private val YES = setOf(
        "네", "내", "예", "네남겨주세요", "내남겨주세요", "예남겨주세요", "남겨주세요", "기록남겨주세요", "네기록남겨주세요", "네남겨줘", "남겨줘", "기록남겨줘",
    )
    private val NO = setOf("아니요", "아니오", "아뇨", "아니", "남기지마세요", "남기지마", "나중에", "나중에요", "아니요나중에")

    fun parse(text: String?): SpokenReply {
        val plain = text.orEmpty().filter { it.isLetterOrDigit() }
        return when (plain) {
            in YES -> SpokenReply.YES
            in NO -> SpokenReply.NO
            else -> SpokenReply.UNCLEAR
        }
    }
}
