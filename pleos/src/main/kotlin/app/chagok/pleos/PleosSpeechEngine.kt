package app.chagok.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import app.chagok.core.voice.SpeechEngine
import app.chagok.core.voice.SpeechEvents
import app.chagok.core.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Pleos TextToSpeech SDK로 말하는 [SpeechEngine]. ON_DEVICE 모드라 네트워크와 앱 등록이 필요 없고, Speech Service(ai.pleos.playground.caas)가 차의 오디오로 재생한다.
 * SDK의 EventListener가 시작과 끝을 알려 주므로 말하는 중인지를 추정하지 않는다. 앱 쪽 speak는 앞 문장을 끊고 시작하므로 줄 세우기는 VoiceChain이 한다.
 *
 * Vehicle SDK가 호출 안에서 리플렉션을 써 첫 호출이 수 초씩 걸리고 메인 스레드에서 부르면 ANR이 났으므로, 이 SDK도 전용 줄에서만 부른다.
 *
 * 부팅 뒤 첫 문장은 Speech Service가 음성 모델을 올리느라 6초쯤 늦게 나온다(2026.11.02 에뮬레이터 로그 'model load elapsed: 6143ms', 두 번째부터 16ms).
 * 그래서 초기화 뒤 공백 한 칸을 말해 모델을 미리 올린다. 소리 데이터가 0바이트라 들리지 않는다(TotalWrittenBytes=0). 대신 끝났다는 알림이 오지 않으므로,
 * 다음 문장이 끊어 주지 않으면 [WARM_UP_HOLD] 뒤에 닫는다. 예열 중에 온 문장은 끊고 바로 시작하고, 모델이 다 올라갈 때까지만 기다린다.
 * 닫은 뒤에도 dumpsys audio에는 다음 문장까지 state:started로 남지만 audio_flinger에서는 비활성이고 출력은 대기 상태다. 그래서 재생 증거는 이 값만으로 삼지 않는다.
 */
class PleosSpeechEngine(context: Context, private val attributes: AudioAttributes) : SpeechEngine {
    override val kind = VoiceEngine.PLEOS_TTS

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var tts: TextToSpeech? = null

    @Volatile
    private var events: SpeechEvents? = null

    // 예열 문장을 어디까지 말했는지. 이 문장의 시작과 끝은 대기열에 알리지 않는다.
    private enum class Warm { NONE, SENT, OPEN, CLOSING }

    @Volatile
    private var warm = Warm.NONE
    private var warmClose: Job? = null

    // 예열 문장을 닫자마자 진짜 문장이 왔다. 곧 올 예열 문장의 onStop을 진짜 문장의 끝으로 보지 않는다.
    @Volatile
    private var ignoreStop = false

    private val listener = object : EventListener {
        override fun onReady() {
            Log.d(TAG, "onReady")
        }

        override fun onStart() {
            Log.i(TAG, "onStart")
            if (warm == Warm.SENT) {
                // 이제부터 온 문장은 예열 문장을 끊고 시작할 수 있다.
                warm = Warm.OPEN
                warmClose = scope.launch {
                    delay(WARM_UP_HOLD)
                    warm = Warm.CLOSING
                    runCatching { tts?.stop() }
                }
                events?.ready()
            } else {
                events?.started()
            }
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            Log.i(TAG, "onDone")
            ignoreStop = false
            if (warm == Warm.NONE) events?.finished() else warmEnded()
        }

        override fun onStop() {
            Log.i(TAG, "onStop")
            when {
                ignoreStop -> ignoreStop = false
                warm == Warm.NONE -> events?.finished()
                else -> warmEnded()
            }
        }

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "onError: $errorType")
            when {
                // 예열 문장의 오류로 엔진을 버리지 않는다. 공백을 받아 주지 않는 버전이어도 말하는 데는 지장이 없고, 정말 안 되면 진짜 문장에서 드러난다.
                warm != Warm.NONE -> warmEnded()
                // 요청 하나가 잘못된 것이면 그 문장만 건너뛴다. 나머지는 엔진 자체를 쓸 수 없는 것으로 보고 다음 엔진으로 넘어간다.
                errorType == ErrorType.INVALID_REQUEST_ERROR -> events?.finished()
                else -> events?.broken(errorType.name)
            }
        }
    }

    override fun prepare(events: SpeechEvents) {
        this.events = events
        calling {
            val sdk = TextToSpeech(app, Mode.ON_DEVICE)
            tts = sdk
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Log.i(TAG, "초기화 성공")
                    calling {
                        sdk.addEventListener(listener)
                        sdk.setLocale(Locale.KOREA)
                        sdk.setAudioAttributes(attributes)
                        warm = Warm.SENT
                        sdk.speak(WARM_UP_TEXT, true)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "초기화 실패: $errorType")
                    this@PleosSpeechEngine.events?.broken(errorType.name)
                }
            })
        }
    }

    override fun speak(text: String) = calling {
        Log.i(TAG, "speak: ${text.length}자")
        ignoreStop = warm == Warm.CLOSING
        warmClose?.cancel()
        warm = Warm.NONE
        tts?.speak(text, true)
    }

    override fun cancel() = calling { tts?.stop() }

    override fun release() {
        events = null
        scope.launch {
            runCatching {
                tts?.removeEventListener(listener)
                tts?.release()
            }
            tts = null
            scope.cancel()
        }
    }

    // 시작 알림 없이 끝나면 준비됐다는 말을 아직 못 한 것이다.
    private fun warmEnded() {
        val before = warm
        warm = Warm.NONE
        warmClose?.cancel()
        Log.i(TAG, "예열 끝")
        if (before == Warm.SENT) events?.ready()
    }

    private fun calling(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "호출 실패: $e")
                events?.broken(e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val TAG = "ChagokVoice"
        const val WARM_UP_TEXT = " "
        val WARM_UP_HOLD = 10.seconds
    }
}
