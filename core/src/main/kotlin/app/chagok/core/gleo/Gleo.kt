package app.chagok.core.gleo

import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.Voice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gleo가 부를 수 있는 함수(assets/schema.json). 이 세 개가 외부 앱이 들어올 수 있는 입구의 전부다.
 * 기록 확정·무효·닫기는 일부러 없다. 음성 명령 한 번으로 되돌릴 수 없는 일이 일어나면 안 되고, 어느 앱이든 같은 인텐트를 보낼 수 있다.
 */
enum class GleoFunction(val action: String) {
    START_RETURN_CHECK("RENTAL_START_RETURN_CHECK"),
    WHAT_TO_CHECK("RENTAL_WHAT_TO_CHECK"),
    SHOW_RECORD("RENTAL_SHOW_RECORD"),
    ;

    companion object {
        fun of(action: String?): GleoFunction? = entries.firstOrNull { it.action == action }
    }
}

enum class GleoStatus(val wire: String) { SUCCESS("success"), FAIL("fail") }

/** VOICE는 Gleo가 message와 data로 답을 만들어 말한다. SILENT는 앱이 직접 말하므로 Gleo가 말하지 않는다. */
enum class GleoMode(val wire: String) { VOICE("voice"), SILENT("silent") }

/**
 * Gleo로 보내는 답(Callback Schema). message는 영어 문장이어야 하고 항목 이름은 한국어를 그대로 쓴다.
 * 응답 브로드캐스트는 대상을 지정하지 않아 다른 앱도 받을 수 있으므로 기록 번호·차대번호는 넣지 않는다.
 */
data class GleoReply(
    val function: GleoFunction,
    val status: GleoStatus,
    val message: String,
    val data: JsonArray? = null,
    val mode: GleoMode = GleoMode.VOICE,
) {
    fun toJson(): String = buildJsonObject {
        put("functionName", function.action)
        put("status", status.wire)
        put("message", message)
        put("mode", mode.wire)
        data?.let { put("data", it.toString()) }
    }.toString()
}

/** [act]가 true면 앱이 그 일을 하고, 어느 쪽이든 [reply]를 보낸다. */
data class GleoDecision(val act: Boolean, val reply: GleoReply)

/** 지금 상태에서 함수를 받을 수 있는지와 무엇을 답할지. 안드로이드 없이 시험한다. */
object GleoCommands {
    private const val MAX_PARAM_LENGTH = 1024

    /**
     * ACTION_PARAM 검증. 함수에 파라미터가 없으니 값은 쓰지 않고, 모양만 본다. 없거나 비었거나 JSON 객체면 받는다.
     * 문서 예시처럼 앞뒤에 큰따옴표가 한 번 더 붙어 오면 벗겨 보고, 그래도 읽을 수 없으면 Gleo가 보낸 것이 아니니 아무것도 하지 않는다.
     */
    fun validParameters(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return true
        if (text.length > MAX_PARAM_LENGTH) return false
        val unwrapped = if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1).trim() else text
        if (unwrapped.isEmpty()) return true
        return try {
            Json.parseToJsonElement(unwrapped) is JsonObject
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun invalidParameters(function: GleoFunction) = fail(function, "The request parameters could not be read, so nothing was done.")

    fun startReturnCheck(state: RentalState, parked: Boolean, damaged: Boolean): GleoDecision {
        val function = GleoFunction.START_RETURN_CHECK
        val refused = unavailable(function, state, damaged)
        return when {
            refused != null -> GleoDecision(false, refused)
            state == RentalState.RETURN_CHECK -> GleoDecision(false, GleoReply(function, GleoStatus.SUCCESS, "The return check is already on the screen."))
            state == RentalState.RETURN -> GleoDecision(false, fail(function, "The return record is being saved."))
            !parked -> GleoDecision(false, fail(function, "The car must be in park before the return check can start."))
            // 반납 체크가 시작되면 앱이 요약을 직접 말한다.
            else -> GleoDecision(true, GleoReply(function, GleoStatus.SUCCESS, "The return check has started and the items to check are on the screen.", mode = GleoMode.SILENT))
        }
    }

    fun showRecord(state: RentalState, parked: Boolean, damaged: Boolean): GleoDecision {
        val function = GleoFunction.SHOW_RECORD
        if (state == RentalState.RETURNED && !damaged) {
            return GleoDecision(false, GleoReply(function, GleoStatus.SUCCESS, "The return report and its QR code are on the screen."))
        }
        val refused = unavailable(function, state, damaged)
        return when {
            refused != null -> GleoDecision(false, refused)
            state != RentalState.IN_RENTAL -> GleoDecision(false, fail(function, "A return check is in progress. The pickup record can be opened after it ends."))
            !parked -> GleoDecision(false, fail(function, "The car must be in park before the record can be shown."))
            else -> GleoDecision(true, GleoReply(function, GleoStatus.SUCCESS, "The pickup record and its QR code are on the screen."))
        }
    }

    /** 지금 물어볼 대여가 없으면 그 답이고, 있으면 null이다. 반납 기록을 저장하는 중에도 차 상태는 답할 수 있다. */
    fun whatToCheckRefusal(state: RentalState, damaged: Boolean): GleoReply? = unavailable(GleoFunction.WHAT_TO_CHECK, state, damaged)

    /** 차를 읽지 못해 7초 안에 답할 수 없거나 읽다 실패했을 때 */
    fun whatToCheckTimedOut() = fail(GleoFunction.WHAT_TO_CHECK, "The vehicle state could not be read in time.")

    /** 앞 요청을 처리하는 중일 때. 어느 앱이든 부를 수 있는 입구라 겹치면 차를 다시 읽지 않는다. */
    fun whatToCheckBusy() = fail(GleoFunction.WHAT_TO_CHECK, "The previous request is still being processed.")

    /** [speakByApp]이 true면 앱이 [speech]를 직접 말하고 Gleo는 말하지 않는다. 금지어 검사를 거친 문구를 그대로 들려주기 위해서다. */
    fun whatToCheck(items: List<CheckItem>, speakByApp: Boolean): GleoReply {
        val actionable = items.filter { it.actionable }
        val energy = items.filterIsInstance<CheckItem.EnergyDrop>()
        val first = if (actionable.isEmpty()) {
            "Nothing needs to be fixed before the return."
        } else {
            "Before the return, ${actionable.size} item(s) need checking: ${actionable.joinToString { it.subject }}."
        }
        val rest = energy.joinToString("") { " ${it.subject} was ${it.before} percent at pickup and is ${it.after} percent now." }
        return GleoReply(
            GleoFunction.WHAT_TO_CHECK, GleoStatus.SUCCESS, first + rest,
            data = buildJsonArray {
                add(buildJsonObject { put("summary", speech(items)) })
                actionable.forEach { add(buildJsonObject { put("item", it.subject); put("state", it.detail) }) }
                energy.forEach {
                    add(buildJsonObject { put("item", it.subject); put("pickupPercent", it.before); put("nowPercent", it.after) })
                }
            },
            mode = if (speakByApp) GleoMode.SILENT else GleoMode.VOICE,
        )
    }

    /** '반납 전에 뭘 확인해야 해?'의 답. 반납 체크 요약(T-04)과 같은 순서이고 반납 기록을 남길지는 묻지 않는다. */
    fun speech(items: List<CheckItem>): String = Voice.returnCheck(items, askToRecord = false)

    // 대여 중이 아니거나 기록을 믿을 수 없으면 어느 함수도 받지 않는다.
    private fun unavailable(function: GleoFunction, state: RentalState, damaged: Boolean): GleoReply? = when {
        damaged -> fail(function, "The saved record could not be verified, so please ask a staff member.")
        state == RentalState.IDLE || state == RentalState.NOTICE || state == RentalState.PICKUP -> fail(function, "No rental is in progress.")
        state == RentalState.RETURNED -> fail(function, "This rental has already been returned. The return report is on the screen.")
        state == RentalState.VOIDED -> fail(function, "This rental was voided, so please ask a staff member.")
        else -> null
    }

    private fun fail(function: GleoFunction, message: String) = GleoReply(function, GleoStatus.FAIL, message)
}
