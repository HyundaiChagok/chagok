package app.chagok.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import app.chagok.core.voice.ReplyListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pleos SpeechToText SDK로 답을 듣는 [ReplyListener]. ON_DEVICE 모드라 소리가 차 밖으로 나가지 않는다.
 * 녹음은 Speech Service(ai.pleos.playground.caas)가 하고 앱에는 문장만 온다. 들은 문장은 로그에 남기지 않는다.
 * 전용 줄에서만 SDK를 부른다. Vehicle SDK처럼 첫 호출이 느릴 수 있어 메인 스레드에서 부르지 않는다.
 *
 * 에뮬레이터(2026.11.02): 시험 음성을 sendAudio로 넣으면 '네', '아니요', '오늘 날씨가 정말 좋네요'를 맞게 인식한다. request()도 Speech Service가 녹음을 시작하지만
 * 이 PC에 마이크 입력이 없어(virtio-snd-mic0 오류) 말소리로는 시험하지 못했다. [testAudio]는 그 시험용이다. 파일이 있으면 마이크 대신 그 소리를 한 번 인식하고 지운다(16kHz 16비트 모노 PCM). 디버그 빌드에서만 넘긴다.
 */
class PleosReplyListener(context: Context, private val testAudio: File? = null) : ReplyListener {
    private val app = context.applicationContext
    private val calls = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + calls)
    private var stt: SpeechToText? = null
    private var ready = CompletableDeferred<Boolean>()

    @Volatile
    private var waiting: CompletableDeferred<String?>? = null

    @Volatile
    private var micOn: CompletableDeferred<Unit>? = null
    private var feeding: Job? = null

    private val listener = object : ResultListener {
        override fun onReady() {
            micOn?.complete(Unit)
        }

        override fun onStartedRecognition() = Unit

        // 끝난 문장만 쓴다. 끝났다는 알림(onEndedRecognition)이 마지막 문장보다 먼저 온다.
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed && stt.isNotBlank()) waiting?.complete(stt)
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onEndedRecognition() = Unit

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "onError: $errorType")
            waiting?.complete(null)
        }
    }

    // 무음을 조금 넣어 인식 엔진을 미리 올린다. 듣는 중이 아니므로 결과는 쓰지 않는다.
    override fun warmUp() = calling {
        if (stt != null) return@calling
        val sdk = SpeechToText(app, Mode.ON_DEVICE)
        stt = sdk
        val done = CompletableDeferred<Boolean>()
        ready = done
        sdk.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                calling {
                    sdk.addListener(listener)
                    sdk.setLocale(Locale.KOREA)
                    sdk.sendAudio(ByteArray(BYTES_PER_100_MS))
                    sdk.completeAudioSend()
                    Log.i(TAG, "준비됨")
                    done.complete(true)
                }
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "초기화 실패: $errorType")
                done.complete(false)
                // 부팅 직후처럼 Speech 서비스가 아직 없었을 수 있다. 다음에 들을 때 다시 해 본다.
                calling { if (stt === sdk) stt = null }
            }
        })
    }

    override suspend fun listen(within: Duration): String? {
        warmUp()
        if (withTimeoutOrNull(READY_WITHIN) { waitForReady() } != true) return null
        val answer = CompletableDeferred<String?>()
        val on = CompletableDeferred<Unit>()
        waiting = answer
        micOn = on
        try {
            start()
            // 요청에서 녹음 시작까지 2초쯤 걸린다(2026.11.02 에뮬레이터). 듣는 시간은 마이크가 켜진 때부터 센다.
            withTimeoutOrNull(MIC_ON_WITHIN) { on.await() }
            return withTimeoutOrNull(within) { answer.await() }
        } finally {
            waiting = null
            micOn = null
            calling {
                feeding?.cancel()
                stt?.stop()
            }
        }
    }

    override fun release() {
        calling {
            stt?.let {
                it.removeListener(listener)
                it.release()
            }
            stt = null
        }
    }

    private suspend fun waitForReady(): Boolean {
        // warmUp이 전용 줄에서 [ready]를 바꾼 뒤에 본다.
        val job = scope.launch { }
        job.join()
        return ready.await()
    }

    private fun start() = calling {
        // 시험 음성은 한 번만 쓴다. 남겨 두면 다음 질문에 저절로 '네'라고 답한다.
        val pcm = testAudio?.takeIf { it.exists() }?.let { file -> file.readBytes().also { file.delete() } }
        if (pcm == null) {
            Log.i(TAG, "듣기 시작")
            stt?.request()
        } else {
            Log.i(TAG, "듣기 시작(시험 음성)")
            micOn?.complete(Unit)
            feeding = scope.launch {
                runCatching {
                    pcm.asList().chunked(BYTES_PER_100_MS).forEach { chunk ->
                        stt?.sendAudio(chunk.toByteArray())
                        delay(100.milliseconds)
                    }
                    stt?.completeAudioSend()
                }
            }
        }
    }

    private fun calling(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "호출 실패: $e")
                waiting?.complete(null)
                ready.complete(false)
            }
        }
    }

    private companion object {
        const val TAG = "ChagokReply"

        // 16kHz 16비트 모노의 100ms
        const val BYTES_PER_100_MS = 3_200
        val READY_WITHIN = 5.seconds
        val MIC_ON_WITHIN = 3.seconds
    }
}
