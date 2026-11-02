package app.chagok.rental

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import app.chagok.core.voice.SpeechEngine
import app.chagok.core.voice.SpeechEvents

/**
 * 말하는 동안 오디오 포커스를 잡는 [SpeechEngine] 껍데기. 포커스를 받지 못하면(통화 중 등) 그 문장을 건너뛰고,
 * 통화처럼 더 중요한 소리가 시작되면 멈춘다. 끊긴 안내를 나중에 다시 말하지 않는다.
 *
 * Pleos TTS는 Speech Service가 대신 재생하면서 포커스를 잡지 않는다(2026.11.02 에뮬레이터 dumpsys audio: 재생 중에도 포커스 스택이 비어 있다).
 * 앱이 잡지 않으면 음악이 줄지 않고 통화 중에도 말한다. 줄 선 문장이 이어지는 동안에는 놓지 않아 문장 사이에 음악이 커졌다 작아지지 않게 한다.
 */
class FocusedSpeech(private val engine: SpeechEngine, context: Context) : SpeechEngine by engine {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var events: SpeechEvents? = null

    @Volatile
    private var hasFocus = false

    @Volatile
    private var spoken = 0

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.i(TAG, "오디오 포커스를 잃어 멈춥니다: $change")
                events?.interrupted()
            }
        }
        .build()

    override fun prepare(events: SpeechEvents) {
        this.events = events
        engine.prepare(object : SpeechEvents by events {
            override fun finished() {
                val before = spoken
                events.finished()
                // 다음 문장이 바로 이어지면 포커스를 놓지 않는다.
                if (spoken == before) abandon()
            }

            override fun broken(reason: String) {
                abandon()
                events.broken(reason)
            }
        })
    }

    override fun speak(text: String) {
        spoken++
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "오디오 포커스 요청: 받음=$hasFocus")
        }
        if (hasFocus) engine.speak(text) else events?.skipped()
    }

    override fun release() {
        abandon()
        engine.release()
    }

    private fun abandon() {
        if (hasFocus) {
            audio.abandonAudioFocusRequest(focus)
            hasFocus = false
            Log.i(TAG, "오디오 포커스 놓음")
        }
    }

    companion object {
        private const val TAG = "ChagokVoice"

        /** 내비게이션 안내처럼 음악을 잠깐 낮추고 말한다. 차에서는 이 용도로 내비게이션 오디오 출력(bus1_navigation_out)을 탄다. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
