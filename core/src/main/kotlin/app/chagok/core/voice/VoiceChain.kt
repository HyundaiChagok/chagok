package app.chagok.core.voice

import app.chagok.core.rental.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 한 번에 하나씩 순서대로 말하는 대기열(기능 정의서 F-VOI-02)이자, 앞 엔진이 안 되면 다음 엔진으로 넘어가는 [VoiceOutput].
 * [engines]는 쓸 순서대로 주고, 필요해질 때 만든다. 앞 엔진이 잘 돌면 뒤 엔진은 만들어지지도 않는다.
 * 모든 엔진이 안 되면 [engine]이 [VoiceEngine.CAPTION_ONLY]가 되고 문장을 버린다. 화면은 같은 문구를 자막으로 보여 준다.
 *
 * Pleos TTS의 콜백에는 문장 번호가 없어서 끊은 문장의 늦은 콜백을 다음 문장과 구별할 수 없다. 그래서 끊고 나면 끝났다는 알림을 받은 뒤에(또는 [CANCEL_WAIT] 뒤에) 다음 문장을 넘긴다.
 * 시간 제한은 엔진이 콜백을 주지 않아 대기열이 멈추는 일을 막는다. 초기화나 시작 알림이 오지 않으면 그 엔진은 안 되는 것으로 보고 다음으로 넘어간다.
 * [speaking]은 말할 문장을 받은 때부터 줄이 빌 때까지 true다. 끝은 엔진의 완료 콜백에 묶여 있고(Pleos TTS는 재생이 끝난 뒤 onDone을 준다), 시작은 콜백을 기다리지 않는다.
 * Pleos TTS의 시작 알림은 소리가 나기 전에, 앞 작업이 모델을 올리는 동안에는 한참 뒤에 오므로 그것을 기다리면 말이 끝났다고 잘못 볼 수 있다.
 */
class VoiceChain(
    private val engines: List<() -> SpeechEngine>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : VoiceOutput {
    private val lock = Any()
    private val queue = ArrayDeque<String>()

    // 지금 엔진의 순번. -1은 아직 아무것도 띄우지 않은 상태, engines.size 이상은 모두 실패한 상태다.
    private var position = -1
    private var current: SpeechEngine? = null
    private var ready = false

    // 엔진에 넘겼고 아직 끝났다는 알림을 받지 못한 문장
    private var active: String? = null
    private var cancelling = false
    private var timer: Job? = null
    private var timerId = 0
    private var retryAllowed = false

    @Volatile
    private var cutOff = false

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _engine = MutableStateFlow(VoiceEngine.CHECKING)
    override val engine: StateFlow<VoiceEngine> = _engine.asStateFlow()

    override val interrupted: Boolean get() = cutOff

    override fun warmUp() = synchronized(lock) {
        // 부팅 직후처럼 Speech 서비스가 아직 없어 모두 실패했으면 화면이 다시 뜰 때 처음부터 다시 해 본다.
        if (current == null) startFrom(0)
    }

    override fun say(text: String, flush: Boolean) = synchronized(lock) {
        if (position < 0 || (current == null && retryAllowed)) startFrom(0)
        if (current == null) return@synchronized
        if (flush) queue.clear()
        queue.addLast(text)
        _speaking.value = true
        when {
            !ready -> Unit
            active == null -> speakNext()
            flush -> cancelActive()
        }
    }

    override fun stop() = synchronized(lock) {
        queue.clear()
        if (active != null) cancelActive()
        _speaking.value = false
    }

    override fun release() = synchronized(lock) {
        queue.clear()
        _speaking.value = false
        _engine.value = VoiceEngine.CHECKING
        startFrom(-1)
    }

    private fun startFrom(index: Int) {
        current?.let { old -> runCatching { old.release() } }
        current = null
        ready = false
        active = null
        cancelling = false
        retryAllowed = false
        cancelTimer()
        position = index
        if (index < 0) return
        val make = engines.getOrNull(index)
        if (make == null) {
            log("쓸 수 있는 음성 엔진이 없어 자막만 보여 줍니다")
            queue.clear()
            _speaking.value = false
            _engine.value = VoiceEngine.CAPTION_ONLY
            scope.launch {
                delay(RETRY_AFTER)
                synchronized(lock) { retryAllowed = true }
            }
            return
        }
        val next = try {
            make()
        } catch (e: Exception) {
            log("엔진을 만들지 못했습니다: $e")
            startFrom(index + 1)
            return
        }
        current = next
        _engine.value = VoiceEngine.CHECKING
        startTimer(PREPARE_WITHIN) { fail("초기화 시간 초과") }
        next.prepare(Events(next))
    }

    private fun speakNext() {
        val text = queue.removeFirstOrNull()
        if (text == null) {
            cancelTimer()
            _speaking.value = false
            return
        }
        active = text
        cancelling = false
        cutOff = false
        startTimer(START_WITHIN) { fail("시작 알림 없음") }
        current?.speak(text)
    }

    private fun cancelActive() {
        if (cancelling) return
        cancelling = true
        startTimer(CANCEL_WAIT) { finishActive() }
        current?.cancel()
    }

    private fun finishActive() {
        active = null
        cancelling = false
        speakNext()
    }

    private fun fail(reason: String) {
        log("${current?.kind} 엔진을 쓸 수 없습니다: $reason")
        // 끊으려던 문장은 다시 말하지 않는다. 말하던 문장은 처음부터 다음 엔진으로 넘긴다.
        if (!cancelling) active?.let(queue::addFirst)
        startFrom(position + 1)
    }

    private fun startTimer(after: Duration, action: () -> Unit) {
        timer?.cancel()
        val id = ++timerId
        timer = scope.launch {
            delay(after)
            synchronized(lock) { if (id == timerId) action() }
        }
    }

    private fun cancelTimer() {
        timerId++
        timer?.cancel()
        timer = null
    }

    // 버린 엔진의 늦은 콜백은 무시한다.
    private inner class Events(private val owner: SpeechEngine) : SpeechEvents {
        private inline fun whileCurrent(block: () -> Unit) = synchronized(lock) { if (current === owner) block() }

        override fun ready() = whileCurrent {
            ready = true
            _engine.value = owner.kind
            cancelTimer()
            log("${owner.kind} 준비됨")
            speakNext()
        }

        override fun started() = whileCurrent {
            val text = active
            if (text != null && !cancelling) {
                // 추정 길이의 세 배까지 기다린다. 그래도 끝났다는 알림이 없으면 끝난 것으로 보고 다음 문장으로 간다.
                startTimer(Voice.estimatedSeconds(text).seconds * 3 + END_MARGIN) { finishActive() }
            }
        }

        override fun finished() = whileCurrent { if (active != null) finishActive() }

        override fun skipped() = whileCurrent {
            cutOff = true
            if (active != null) finishActive()
        }

        override fun interrupted() = whileCurrent {
            cutOff = true
            queue.clear()
            if (active != null) cancelActive()
            _speaking.value = false
        }

        override fun broken(reason: String) = whileCurrent { fail(reason) }
    }

    private companion object {
        // Pleos TTS는 연결 4초, 모델 예열 6초가 걸렸다(차가 바쁠 때는 더 걸린다).
        val PREPARE_WITHIN = 30.seconds

        // 음성 모델을 처음 올릴 때는 시작 알림이 수십 초 늦는다(2026.11.02 에뮬레이터: Speech Service를 막 띄운 뒤 25초). 그동안을 고장으로 보면 멀쩡한 엔진을 버리고 자막만 남는다.
        val START_WITHIN = 40.seconds
        val CANCEL_WAIT = 1.seconds
        val END_MARGIN = 5.seconds
        val RETRY_AFTER = 30.seconds
    }
}
