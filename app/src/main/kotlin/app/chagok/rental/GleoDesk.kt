package app.chagok.rental

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.chagok.ChagokApplication
import app.chagok.core.gleo.GleoCommands
import app.chagok.core.gleo.GleoFunction
import app.chagok.core.gleo.GleoMode
import app.chagok.core.gleo.GleoReply
import app.chagok.core.rental.RentalPolicy
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ReturnCheck
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.VoiceEngine
import app.chagok.core.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 화면 없이 답하는 Gleo 함수(RENTAL_WHAT_TO_CHECK). '반납 전에 뭘 확인해야 해?'에 지금 고칠 것과 배터리 차이를 답한다.
 * 프로세스가 죽어 있어도 불릴 수 있어 저장소와 차만 보고 답한다. Gleo는 7초 뒤 호출을 취소하므로 [ANSWER_WITHIN] 안에 답을 보낸다.
 *
 * P단이면 앱이 금지어 검사를 거친 문구를 직접 말하고 Gleo는 말하지 않는다(mode silent). 승인 전에도 시연에서 들리고, 대화 모델이 문구를 바꾸지 않는다.
 * 주행 중이거나 앱이 말할 수 없으면 앱은 말하지 않고(요구사항 SAF-02) 사용자가 부른 Gleo가 답하게 한다.
 */
class GleoDesk(
    private val store: RecordStore,
    private val vehicle: VehicleSource,
    private val policy: RentalPolicy,
    private val parked: Flow<Boolean>,
    private val voice: () -> VoiceOutput,
    private val respond: (GleoReply) -> Unit,
) {
    private val busy = Mutex()

    private val _spoken = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 앱이 직접 말한 문장. 화면이 떠 있으면 자막으로 보여 준다. */
    val spoken: SharedFlow<String> = _spoken

    suspend fun whatToCheck(rawParam: String?) {
        if (!GleoCommands.validParameters(rawParam)) return respond(GleoCommands.invalidParameters(GleoFunction.WHAT_TO_CHECK))
        // 어느 앱이든 부를 수 있는 입구다. 앞 요청이 끝나기 전에는 차를 다시 읽지 않는다.
        if (!busy.tryLock()) return respond(GleoCommands.whatToCheckBusy())
        try {
            val reply = try {
                withTimeoutOrNull(ANSWER_WITHIN) { answer() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChagokGleo", "답을 만들지 못했습니다: $e")
                null
            }
            respond(reply ?: GleoCommands.whatToCheckTimedOut())
        } finally {
            busy.unlock()
        }
    }

    private suspend fun answer(): GleoReply {
        val saved = store.readSession()
        val session = (saved as? Stored.Found)?.value
        val refusal = when (saved) {
            is Stored.Damaged -> GleoCommands.whatToCheckRefusal(RentalState.IN_RENTAL, damaged = true)
            Stored.Missing -> GleoCommands.whatToCheckRefusal(RentalState.IDLE, damaged = false)
            is Stored.Found -> GleoCommands.whatToCheckRefusal(saved.value.state, damaged = false)
        }
        if (refusal != null) return refusal
        val pickup = session?.sessionId?.let { (store.readPickup(it) as? Stored.Found)?.value }
            ?: return GleoCommands.whatToCheckRefusal(RentalState.IN_RENTAL, damaged = true)!!
        val items = ReturnCheck.items(pickup.state, vehicle.state(), policy)
        val speak = withTimeoutOrNull(GEAR_WITHIN) { parked.catch { }.firstOrNull() } == true && voice().engine.value != VoiceEngine.CAPTION_ONLY
        val reply = GleoCommands.whatToCheck(items, speakByApp = speak)
        if (speak && reply.mode == GleoMode.SILENT) {
            val text = GleoCommands.speech(items)
            voice().say(text, flush = true)
            _spoken.tryEmit(text)
        }
        return reply
    }

    private companion object {
        val ANSWER_WITHIN = 6.seconds
        val GEAR_WITHIN = 1500.milliseconds
    }
}

/** 화면 없이 오는 Gleo 호출. 내보내져 있어 어느 앱이든 보낼 수 있으므로 정해진 함수만 받고, 답을 보내면 끝난다. */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = GleoFunction.of(intent.action)
        if (function != GleoFunction.WHAT_TO_CHECK) return
        // 망가진 Parcelable을 넣어 보내도 죽지 않는다.
        val raw = try {
            intent.getStringExtra(GleoResponder.ACTION_PARAM)
        } catch (e: RuntimeException) {
            GleoResponder.UNREADABLE
        }
        val done = goAsync()
        scope.launch {
            try {
                // AppGraph를 처음 만드는 일이 수 초 걸릴 수 있어 메인 스레드에서 만들지 않는다.
                (context.applicationContext as ChagokApplication).graph.gleo.whatToCheck(raw)
            } catch (e: Exception) {
                Log.w(TAG, "답하지 못했습니다: $e")
            } finally {
                done.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ChagokGleo"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Gleo로 답을 보낸다(Callback). 문서대로 대상 없는 브로드캐스트다. 어떤 앱이 받아도 되는 값만 담는다([GleoReply]). */
class GleoResponder(context: Context) {
    private val app = context.applicationContext

    fun send(reply: GleoReply) {
        Log.i(TAG, "${reply.function.action}: ${reply.status.wire}, ${reply.mode.wire}, ${reply.message}")
        runCatching {
            app.sendBroadcast(
                Intent(RESPONSE).apply {
                    putExtra("packageName", app.packageName)
                    putExtra("response", reply.toJson())
                },
            )
        }.onFailure { Log.w(TAG, "답을 보내지 못했습니다: $it") }
    }

    companion object {
        private const val TAG = "ChagokGleo"
        private const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        const val ACTION_PARAM = "ACTION_PARAM"

        /** 인텐트에서 파라미터를 꺼내다 실패했을 때 넘기는 값. 파라미터 검사에 걸린다. */
        const val UNREADABLE = "unreadable"
    }
}
