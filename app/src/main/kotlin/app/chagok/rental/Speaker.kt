package app.chagok.rental

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 음성 안내(기능 정의서 F-VOI-02). 한 번에 하나씩 순서대로 말한다. TTS를 쓸 수 없으면 조용히 넘어가고,
 * 화면은 같은 문구를 자막으로 보여 준다. 언제 말해도 되는지는 [RentalController]가 정한다.
 * [speaking]은 자막 띠에 지금 말하는 중인지를 보여 주는 데 쓴다.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val pending = mutableListOf<String>()
    private val inFlight = AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    // 엔진 스레드에서 불린다. 줄 선 문장이 모두 끝나야 말하기가 끝난 것으로 본다.
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _speaking.value = true
        }

        override fun onDone(utteranceId: String?) = finished()

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

        @Deprecated("API 21부터 onError(String, Int)가 있지만 추상 메서드라 함께 구현한다.")
        override fun onError(utteranceId: String?) = finished()

        override fun onError(utteranceId: String?, errorCode: Int) = finished()
    }

    @Volatile
    private var ready = false

    // TTS 엔진에 따라 생성자 안에서 바로 onInit을 부르므로, 위의 필드를 모두 만든 뒤에 만든다.
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        if (ready) tts.setOnUtteranceProgressListener(progress)
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (ready) queued.forEach(::say)
    }

    fun say(text: String) {
        if (ready) {
            inFlight.incrementAndGet()
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        } else {
            synchronized(pending) { pending += text }
        }
    }

    fun stop() {
        tts.stop()
        inFlight.set(0)
        _speaking.value = false
    }

    private fun finished() {
        if (inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            _speaking.value = false
        }
    }
}
