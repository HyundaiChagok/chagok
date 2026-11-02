package app.chagok.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** 지금 쓰는 음성 엔진. 직원 화면에 그대로 밝힌다. */
enum class VoiceEngine(val label: String) {
    /** 아직 초기화 중이다. 이 동안 온 문장은 줄을 서 있다가 준비되면 나간다. */
    CHECKING("확인 중"),
    PLEOS_TTS("Pleos TTS"),
    ANDROID_TTS("Android TTS"),

    /** 어느 엔진도 쓸 수 없다. 화면의 자막으로만 안내한다. */
    CAPTION_ONLY("자막만"),
}

/**
 * 음성을 내보내는 자리. 언제 무엇을 말할지는 앱의 컨트롤러가 정하고, 구현은 소리만 낸다. 구현은 [VoiceChain]이다.
 * 말할 수 없는 상황이면 조용히 넘어간다. 화면은 같은 문구를 자막으로 보여 준다.
 */
interface VoiceOutput {
    /** 말할 문장이 남아 있는지. 자막 띠의 막대에 쓰고, 말이 끝난 뒤에 듣는 데 쓴다. */
    val speaking: StateFlow<Boolean>

    /** 마지막 안내를 통화처럼 더 중요한 소리 때문에 건너뛰었거나 끊었는지. 고객이 질문을 듣지 못했고 다른 사람과 말하는 중일 수 있으니 답을 듣지 않는다. */
    val interrupted: Boolean get() = false

    /** 지금 쓰는 엔진 */
    val engine: StateFlow<VoiceEngine>

    /** [flush]가 true면 말하던 것과 줄 선 것을 버리고 바로 말한다. false면 앞 안내가 끝난 뒤에 말한다. */
    fun say(text: String, flush: Boolean = false)

    /** 말하던 것과 줄 선 것을 모두 버린다. */
    fun stop()

    /** 첫 안내가 늦지 않게 엔진을 미리 띄운다. 화면이 뜰 때 부른다. 대여 중 기록 서비스만 도는 동안에는 부르지 않는다. */
    fun warmUp() {}

    /** 엔진을 내린다. 화면이 없어져 대여 중 기록 서비스만 남을 때 부른다. 그 뒤에 말할 일이 생기면 다시 띄운다. */
    fun release() {}
}

/**
 * 소리를 내는 엔진 하나. 대기열과 엔진 교체는 [VoiceChain]이 맡고, 엔진은 문장 하나를 말하고 어떻게 됐는지 알리기만 한다.
 * 어느 메서드도 부른 스레드를 오래 잡아 두면 안 된다. Pleos SDK는 첫 호출이 수 초씩 걸리므로 구현이 자기 줄로 넘긴다.
 */
interface SpeechEngine {
    val kind: VoiceEngine

    /** 초기화를 시작한다. 결과는 [SpeechEvents.ready]나 [SpeechEvents.broken]으로 알린다. */
    fun prepare(events: SpeechEvents)

    /** 앞 문장이 끝난 뒤에만 불린다. */
    fun speak(text: String)

    /** 말하던 것을 끊는다. 끊기면 [SpeechEvents.finished]로 알린다. */
    fun cancel()

    fun release()
}

/** 엔진이 알리는 일. 어느 스레드에서 불러도 된다. */
interface SpeechEvents {
    fun ready()

    /** 소리가 나기 시작했다. */
    fun started()

    /** 이 문장이 끝났다. 다 말했거나 끊겼다. */
    fun finished()

    /** 오디오 포커스를 받지 못해(통화 중 등) 이 문장을 건너뛰었다. */
    fun skipped()

    /** 통화처럼 더 중요한 소리가 시작됐다. 끊긴 안내를 나중에 다시 말하지 않도록 줄 선 것까지 버린다. */
    fun interrupted()

    /** 이 엔진을 더 쓸 수 없다. 다음 엔진으로 넘어간다. */
    fun broken(reason: String)
}

/**
 * 말하는 동안 P단에서 벗어나면 멈춘다(요구사항 SAF-02). 화면이 떠 있으면 컨트롤러도 기어를 듣지만, 내비가 앞에 있을 때의 도착 안내나 화면 없이 하는 Gleo 답은 아무도 듣지 않는다.
 * 말하는 동안에만 기어를 듣는다. 기어를 읽지 못하면 멈추지 않는다.
 */
fun CoroutineScope.stopSpeakingWhenDriving(voice: VoiceOutput, parked: Flow<Boolean>): Job = launch {
    voice.speaking.collectLatest { speaking ->
        if (speaking && parked.catch { }.firstOrNull { !it } != null) voice.stop()
    }
}
