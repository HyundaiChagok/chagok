package app.chagok.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 말하라고 받은 것을 적어 두는 가짜 엔진. 알림은 시험이 직접 보낸다. */
private class FakeEngine(override val kind: VoiceEngine, private val readyAtOnce: Boolean = true) : SpeechEngine {
    val spoken = mutableListOf<String>()
    var cancels = 0
    var released = false
    lateinit var events: SpeechEvents

    override fun prepare(events: SpeechEvents) {
        this.events = events
        if (readyAtOnce) events.ready()
    }

    override fun speak(text: String) {
        spoken += text
    }

    override fun cancel() {
        cancels++
    }

    override fun release() {
        released = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChainTest {
    private val pleos = FakeEngine(VoiceEngine.PLEOS_TTS)
    private val android = FakeEngine(VoiceEngine.ANDROID_TTS)
    private var androidMade = 0

    private fun chain(scope: CoroutineScope, first: SpeechEngine = pleos, second: SpeechEngine = android) = VoiceChain(
        listOf({ first }, { androidMade++; second }),
        scope,
    )

    private fun TestScope.after(seconds: Long) {
        advanceTimeBy(seconds * 1000)
        runCurrent()
    }

    @Test
    fun `한 번에 한 문장씩 순서대로 말한다`() = runTest {
        val voice = chain(backgroundScope)
        assertFalse(voice.speaking.value)
        voice.say("하나")
        voice.say("둘")
        assertEquals(listOf("하나"), pleos.spoken)
        assertTrue(voice.speaking.value, "문장을 받으면 끝날 때까지 말하는 중이다")

        pleos.events.started()
        pleos.events.finished()
        assertEquals(listOf("하나", "둘"), pleos.spoken)
        assertTrue(voice.speaking.value, "줄 선 문장이 이어지면 말하는 중으로 남는다")

        pleos.events.started()
        pleos.events.finished()
        assertFalse(voice.speaking.value)
    }

    @Test
    fun `끊고 말하면 끝났다는 알림 뒤에 새 문장을 말하고 줄 선 것을 버린다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("요약")
        voice.say("줄 선 문장")
        pleos.events.started()

        voice.say("창문이 닫혔습니다", flush = true)
        assertEquals(1, pleos.cancels)
        assertEquals(listOf("요약"), pleos.spoken, "끝났다는 알림 전에는 넘기지 않는다. SDK 콜백에 문장 번호가 없다")

        pleos.events.finished()
        assertEquals(listOf("요약", "창문이 닫혔습니다"), pleos.spoken)
    }

    @Test
    fun `끊었는데 알림이 없으면 1초 뒤에 다음 문장으로 간다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("요약")
        pleos.events.started()
        voice.say("새 문장", flush = true)
        runCurrent()

        after(1)
        assertEquals(listOf("요약", "새 문장"), pleos.spoken)
    }

    @Test
    fun `끊는 동안 또 끊어도 마지막 문장만 말한다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("요약")
        pleos.events.started()
        voice.say("둘", flush = true)
        voice.say("셋", flush = true)
        assertEquals(1, pleos.cancels)

        pleos.events.finished()
        assertEquals(listOf("요약", "셋"), pleos.spoken)
    }

    @Test
    fun `멈추면 줄 선 것까지 버린다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        voice.say("둘")
        pleos.events.started()

        voice.stop()
        assertEquals(1, pleos.cancels)
        assertFalse(voice.speaking.value)

        pleos.events.finished()
        assertEquals(listOf("하나"), pleos.spoken)
    }

    @Test
    fun `준비되기 전에 온 문장은 준비되면 말한다`() = runTest {
        val slow = FakeEngine(VoiceEngine.PLEOS_TTS, readyAtOnce = false)
        val voice = chain(backgroundScope, first = slow)
        voice.warmUp()
        assertEquals(VoiceEngine.CHECKING, voice.engine.value)

        voice.say("앞 문장")
        assertTrue(voice.speaking.value, "줄을 서 있어도 말이 끝난 것이 아니다")
        voice.say("뒷 문장", flush = true)
        assertEquals(emptyList(), slow.spoken)

        slow.events.ready()
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)
        assertEquals(listOf("뒷 문장"), slow.spoken)
    }

    @Test
    fun `앞 엔진이 잘 돌면 뒤 엔진은 만들지 않는다`() = runTest {
        val voice = chain(backgroundScope)
        voice.warmUp()
        voice.warmUp()
        voice.say("하나")
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)
        assertEquals(0, androidMade)
    }

    @Test
    fun `초기화에 실패하면 줄 선 문장을 다음 엔진으로 넘긴다`() = runTest {
        val broken = FakeEngine(VoiceEngine.PLEOS_TTS, readyAtOnce = false)
        val voice = chain(backgroundScope, first = broken)
        voice.say("하나")

        broken.events.broken("ON_DEVICE_ENGINE_ERROR")
        assertEquals(VoiceEngine.ANDROID_TTS, voice.engine.value)
        assertEquals(listOf("하나"), android.spoken)
        assertTrue(broken.released)
    }

    @Test
    fun `말하다 실패하면 그 문장부터 다음 엔진으로 말한다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        voice.say("둘")
        pleos.events.started()

        pleos.events.broken("AUDIO_ERROR")
        assertEquals(listOf("하나"), android.spoken)
        android.events.started()
        android.events.finished()
        assertEquals(listOf("하나", "둘"), android.spoken)
    }

    @Test
    fun `끊으려던 문장은 엔진이 망가져도 다시 말하지 않는다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        pleos.events.started()
        voice.stop()

        pleos.events.broken("AUDIO_ERROR")
        assertEquals(emptyList(), android.spoken)
    }

    @Test
    fun `모든 엔진이 안 되면 자막만이라고 드러내고 문장을 버린다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        pleos.events.broken("PERMISSION_DENIED")
        android.events.broken("status=-1")

        assertEquals(VoiceEngine.CAPTION_ONLY, voice.engine.value)
        assertFalse(voice.speaking.value)

        voice.say("둘")
        assertEquals(listOf("하나"), android.spoken)
    }

    @Test
    fun `모두 실패해도 화면이 다시 뜨면 처음부터 다시 해 본다`() = runTest {
        val voice = chain(backgroundScope)
        voice.warmUp()
        pleos.events.broken("TIMEOUT")
        android.events.broken("status=-1")
        assertEquals(VoiceEngine.CAPTION_ONLY, voice.engine.value)

        voice.warmUp()
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)
    }

    @Test
    fun `모두 실패해도 30초가 지나면 말할 때 다시 해 본다`() = runTest {
        val voice = chain(backgroundScope)
        voice.warmUp()
        pleos.events.broken("TIMEOUT")
        android.events.broken("status=-1")

        after(10)
        voice.say("이르다")
        assertEquals(VoiceEngine.CAPTION_ONLY, voice.engine.value)

        after(21)
        voice.say("다시")
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)
        assertEquals(listOf("다시"), pleos.spoken)
    }

    @Test
    fun `초기화 알림이 오지 않으면 30초 뒤에 다음 엔진으로 넘어간다`() = runTest {
        val silent = FakeEngine(VoiceEngine.PLEOS_TTS, readyAtOnce = false)
        val voice = chain(backgroundScope, first = silent)
        voice.say("하나")

        after(29)
        assertEquals(VoiceEngine.CHECKING, voice.engine.value)
        after(1)
        assertEquals(VoiceEngine.ANDROID_TTS, voice.engine.value)
        assertEquals(listOf("하나"), android.spoken)
    }

    @Test
    fun `음성 모델을 올리는 동안은 기다리고 시작 알림이 끝내 오지 않으면 그 엔진은 안 되는 것으로 본다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        runCurrent()

        after(30)
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value, "에뮬레이터에서 첫 문장이 25초 늦은 적이 있다")
        after(10)
        assertEquals(VoiceEngine.ANDROID_TTS, voice.engine.value)
        assertEquals(listOf("하나"), android.spoken)
    }

    @Test
    fun `끝 알림이 오지 않아도 대기열이 멈추지 않는다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("가나다")
        voice.say("둘")
        pleos.events.started()
        runCurrent()

        // 세 음절은 0.6초로 추정한다. 세 배 + 여유 5초 뒤에는 끝난 것으로 본다.
        after(7)
        assertEquals(listOf("가나다", "둘"), pleos.spoken)
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)
    }

    @Test
    fun `버린 엔진의 늦은 알림은 무시한다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        voice.say("둘")
        pleos.events.broken("AUDIO_ERROR")
        assertEquals(listOf("하나"), android.spoken)

        pleos.events.finished()
        pleos.events.started()
        assertEquals(listOf("하나"), android.spoken)
        assertTrue(voice.speaking.value, "뒤 엔진이 아직 말하는 중이다")
    }

    @Test
    fun `더 중요한 소리가 시작되면 줄 선 것까지 버리고 다시 말하지 않는다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("하나")
        voice.say("둘")
        pleos.events.started()

        pleos.events.interrupted()
        assertFalse(voice.speaking.value)
        pleos.events.finished()
        assertEquals(listOf("하나"), pleos.spoken)
    }

    @Test
    fun `이 문장만 건너뛰면 다음 문장으로 가고 끊겼는지 안다`() = runTest {
        val voice = chain(backgroundScope)
        voice.say("포커스를 받지 못한 문장")
        pleos.events.skipped()
        assertTrue(voice.interrupted, "고객이 듣지 못했다")
        assertFalse(voice.speaking.value)
        assertEquals(VoiceEngine.PLEOS_TTS, voice.engine.value)

        voice.say("둘")
        assertFalse(voice.interrupted, "다음 문장을 말하면 풀린다")
        assertEquals(listOf("포커스를 받지 못한 문장", "둘"), pleos.spoken)

        pleos.events.started()
        pleos.events.interrupted()
        assertTrue(voice.interrupted)
    }

    @Test
    fun `말하는 동안 P단에서 벗어나면 멈춘다`() = runTest {
        val parked = MutableStateFlow(true)
        val voice = chain(backgroundScope)
        backgroundScope.stopSpeakingWhenDriving(voice, parked)
        runCurrent()
        voice.say("도착했습니다")
        voice.say("줄 선 문장")
        runCurrent()
        assertEquals(0, pleos.cancels)

        parked.value = false
        runCurrent()
        assertEquals(1, pleos.cancels)
        assertFalse(voice.speaking.value)
        pleos.events.finished()
        assertEquals(listOf("도착했습니다"), pleos.spoken)
    }

    @Test
    fun `이미 주행 중이면 바로 멈추고 기어를 읽지 못하면 멈추지 않는다`() = runTest {
        val voice = chain(backgroundScope)
        backgroundScope.stopSpeakingWhenDriving(voice, flowOf(false))
        runCurrent()
        voice.say("하나")
        runCurrent()
        assertEquals(1, pleos.cancels)

        val other = FakeEngine(VoiceEngine.PLEOS_TTS)
        val unknown = VoiceChain(listOf({ other }), backgroundScope)
        backgroundScope.stopSpeakingWhenDriving(unknown, emptyFlow())
        runCurrent()
        unknown.say("둘")
        runCurrent()
        assertEquals(0, other.cancels)
    }

    @Test
    fun `내린 뒤에 말할 일이 생기면 다시 띄운다`() = runTest {
        val first = FakeEngine(VoiceEngine.PLEOS_TTS)
        val second = FakeEngine(VoiceEngine.PLEOS_TTS)
        val engines = ArrayDeque(listOf(first, second))
        val voice = VoiceChain(listOf({ engines.removeFirst() }), backgroundScope)
        voice.warmUp()
        voice.release()
        assertTrue(first.released)
        assertEquals(VoiceEngine.CHECKING, voice.engine.value)

        voice.say("도착했습니다")
        assertEquals(listOf("도착했습니다"), second.spoken)
    }

    @Test
    fun `엔진을 만들다 실패해도 다음 엔진으로 넘어간다`() = runTest {
        val voice = VoiceChain(listOf({ error("SDK 없음") }, { android }), backgroundScope)
        voice.say("하나")
        assertEquals(VoiceEngine.ANDROID_TTS, voice.engine.value)
        assertEquals(listOf("하나"), android.spoken)
    }
}
