package app.chagok.core.record

import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleSnapshot
import app.chagok.core.model.VehicleState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

enum class SnapshotKind { PICKUP, RETURN }

/**
 * 인수·반납 기록 한 건. 저장, 전송, 무결성 계산은 모두 [toJson]을 정규화한 문자열을 기준으로 한다.
 *
 * [state]는 [VehicleState.forRecord]로 만든 것이어야 한다. [items]에 없는 값은 기록하지 않는다.
 */
data class SnapshotRecord(
    val sessionId: String,
    val recordNo: String,
    val kind: SnapshotKind,
    val vehicleId: String,
    val capturedAt: Instant,
    val state: VehicleState,
    val items: Set<StateItem>,
    val appVersion: String,
    val rulesVersion: String,
    val demo: Boolean = false,
) {
    /** hash를 뺀 기록. 무결성 값은 이 JSON으로 계산한다. */
    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("sessionId", sessionId)
        put("recordNo", recordNo)
        put("kind", kind.name)
        put("vehicleId", vehicleId)
        put("capturedAt", capturedAt.toString())
        put("app", appVersion)
        put("rules", rulesVersion)
        put("demo", demo)
        put("readings", StateJson.encode(state, items))
    }

    /** 저장·전송용. 받는 쪽은 hash가 내용과 맞는지 확인한다. */
    fun toJsonWithHash(): JsonObject = JsonObject(toJson() + ("hash" to JsonPrimitive(hash)))

    // 화면이 다시 그려질 때마다 읽으므로 한 번만 계산한다.
    val hash: String by lazy { Canonical.sha256(Canonical.encode(toJson())) }

    fun toSnapshot(): VehicleSnapshot = VehicleSnapshot(capturedAt, null, state)

    companion object {
        const val SCHEMA = "snapshot/1"

        /** [toJson]이나 [toJsonWithHash]로 만든 JSON을 되돌린다. hash가 있으면 내용과 맞는지 확인한다. */
        fun fromJson(json: JsonObject): SnapshotRecord {
            require(json["schema"]?.jsonPrimitive?.content == SCHEMA) { "모르는 기록 형식: ${json["schema"]}" }
            val readings = json.getValue("readings").jsonObject
            val record = SnapshotRecord(
                sessionId = json.text("sessionId"),
                recordNo = json.text("recordNo"),
                kind = SnapshotKind.valueOf(json.text("kind")),
                vehicleId = json.text("vehicleId"),
                capturedAt = Instant.parse(json.text("capturedAt")),
                state = StateJson.decode(readings),
                items = StateItem.entries.filter { it.key in readings }.toSet(),
                appVersion = json.text("app"),
                rulesVersion = json.text("rules"),
                demo = json.getValue("demo").jsonPrimitive.boolean,
            )
            json["hash"]?.jsonPrimitive?.content?.let { require(it == record.hash) { "무결성 값이 기록 내용과 맞지 않습니다" } }
            return record
        }
    }
}
