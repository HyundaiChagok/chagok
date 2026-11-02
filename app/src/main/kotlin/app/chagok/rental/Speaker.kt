package app.chagok.rental

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.chagok.core.voice.SpeechEngine
import app.chagok.core.voice.SpeechEvents
import app.chagok.core.voice.VoiceEngine
import java.util.Locale

/**
 * Android TTS로 말하는 [SpeechEngine](기능 정의서 F-VOI-02). Pleos TTS를 쓸 수 없을 때의 대안이다.
 * Pleos 에뮬레이터에서는 기본 엔진이 없어 status=-1로 실패한다. TTS_SERVICE를 선언한 ai.pleos.playground.caas를 엔진으로 직접 지정해도 status=-1이다(2026.11.02 확인).
 */
class Speaker(context: Context) : SpeechEngine, TextToSpeech.OnInitListener {
    override val kind = VoiceEngine.ANDROID_TTS

    private val app = context.applicationContext
    private var events: SpeechEvents? = null
    private var tts: TextToSpeech? = null
    private var sequence = 0

    // 엔진 스레드에서 불린다.
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            events?.started()
        }

        override fun onDone(utteranceId: String?) {
            events?.finished()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            events?.finished()
        }

        @Deprecated("API 21부터 onError(String, Int)가 있지만 추상 메서드라 함께 구현한다.")
        override fun onError(utteranceId: String?) {
            events?.finished()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            events?.finished()
        }
    }

    override fun prepare(events: SpeechEvents) {
        this.events = events
        tts = TextToSpeech(app, this)
    }

    override fun onInit(status: Int) {
        val engine = tts
        val usable = status == TextToSpeech.SUCCESS && engine != null && engine.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        Log.i(TAG, "Android TTS 초기화: status=$status, usable=$usable")
        if (usable && engine != null) {
            engine.setAudioAttributes(FocusedSpeech.ATTRIBUTES)
            engine.setOnUtteranceProgressListener(progress)
            events?.ready()
        } else {
            events?.broken("status=$status")
        }
    }

    override fun speak(text: String) {
        val engine = tts ?: return
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chagok-${++sequence}") != TextToSpeech.SUCCESS) events?.finished()
    }

    override fun cancel() {
        tts?.stop()
    }

    override fun release() {
        tts?.shutdown()
        tts = null
        events = null
    }

    private companion object {
        const val TAG = "ChagokVoice"
    }
}
