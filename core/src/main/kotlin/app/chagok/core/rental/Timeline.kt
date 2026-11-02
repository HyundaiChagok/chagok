package app.chagok.core.rental

import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.record.Canonical
import app.chagok.core.record.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/** 리포트에 넣는 대여 중 변화 한 줄(기능 정의서 F-TML-01). 사진이 남기지 못하는 값이다. */
data class TimelineEntry(val at: Instant, val note: String)

/**
 * 앱이 꺼져 있었거나 차 데이터 구독이 끊겨 변화를 볼 수 없던 구간(F-TML-02, 요구사항 SFR-14).
 * 이 구간은 '변화 없음'이 아니라 '모름'이다.
 */
data class RecordGap(val from: Instant, val to: Instant)

/**
 * 대여 중 이벤트를 인수 기록과 견주어 문구로 바꾼다. 리스너는 구독 직후 현재 값을 한 번 주기도 하고 같은 값을 다시 주기도
 * 하므로, 직전에 알던 값과 달라진 것만 문구가 된다. 인수 때 읽지 못한 값은 '바뀌었다'고 말하지 않는다.
 */
class TimelineFold(pickup: VehicleState) {
    private var telltales: Set<Telltale>? = pickup.telltales
    private val fluids = mutableMapOf(
        StateItem.ENGINE_OIL to pickup.engineOil,
        StateItem.BRAKE_FLUID to pickup.brakeFluid,
        StateItem.WASHER_FLUID to pickup.washerFluid,
    )
    private val airbags = pickup.airbagsDeployed.toMutableMap()

    /** 바뀐 것이 있으면 문구를, 없으면 빈 목록을 돌려준다. */
    fun apply(event: VehicleEvent): List<String> = when (event) {
        is VehicleEvent.TelltalesChanged -> telltales(event.active)
        is VehicleEvent.FluidChanged -> fluid(event.item, event.level)
        is VehicleEvent.AirbagChanged -> airbag(event.area, event.deployed)
        else -> emptyList()
    }

    private fun telltales(active: Set<Telltale>): List<String> {
        val before = telltales
        telltales = active
        if (before == null) return active.sortedBy { it.code }.map { "경고등 켜져 있음: ${it.label}" }
        return (active - before).sortedBy { it.code }.map { "경고등 켜짐: ${it.label}" } +
            (before - active).sortedBy { it.code }.map { "경고등 꺼짐: ${it.label}" }
    }

    private fun fluid(item: StateItem, level: FluidLevel): List<String> {
        val before = fluids.put(item, level)
        return when {
            before == level -> emptyList()
            before != null -> listOf("${item.label} ${before.label} → ${level.label}")
            level == FluidLevel.LOW -> listOf("${item.label} ${level.label}")
            else -> emptyList()
        }
    }

    private fun airbag(area: Area, deployed: Boolean): List<String> {
        val before = airbags.put(area, deployed)
        return when {
            before == deployed -> emptyList()
            deployed -> listOf("에어백 전개 기록: ${area.label}")
            before == true -> listOf("에어백 전개 기록 꺼짐: ${area.label}")
            else -> emptyList()
        }
    }
}

/** timeline.jsonl의 한 줄 */
sealed interface TimelineLine {
    data class Event(val logged: LoggedEvent) : TimelineLine
    data class Gap(val gap: RecordGap) : TimelineLine
}

/**
 * timeline.jsonl 형식. [Rental.recordsDuringRental]을 통과한 이벤트와 기록 공백만 자리가 있다.
 * 위치와 문 열림·착석은 형식에 자리가 없어 남길 수 없다(요구사항 SFR-13).
 */
object TimelineJson {
    /** 형식에 자리가 없는 이벤트면 null */
    fun encode(line: TimelineLine): String? {
        val json = when (line) {
            is TimelineLine.Gap -> buildJsonObject {
                put("type", "gap")
                put("from", line.gap.from.toString())
                put("to", line.gap.to.toString())
            }
            is TimelineLine.Event -> event(line.logged)
        }
        return json?.let { Canonical.encode(it) }
    }

    /** 앱이 쓰는 도중 꺼지면 마지막 줄이 잘릴 수 있다. 읽을 수 없는 줄은 null이고, 그 시점은 기록 공백에 들어간다. */
    fun decode(text: String): TimelineLine? = try {
        val json = Json.parseToJsonElement(text).jsonObject
        when (val type = json.text("type")) {
            "gap" -> TimelineLine.Gap(RecordGap(Instant.parse(json.text("from")), Instant.parse(json.text("to"))))
            else -> TimelineLine.Event(LoggedEvent(Instant.parse(json.text("at")), null, event(type, json)))
        }
    } catch (e: Exception) {
        null
    }

    private fun event(logged: LoggedEvent): JsonObject? {
        val fields: JsonObject = when (val e = logged.event) {
            is VehicleEvent.TelltalesChanged -> buildJsonObject {
                put("type", "telltales")
                put(
                    "active",
                    buildJsonArray {
                        e.active.sortedBy { it.code }.forEach { t ->
                            add(buildJsonObject { put("code", t.code); put("label", t.label) })
                        }
                    },
                )
            }
            is VehicleEvent.FluidChanged -> buildJsonObject {
                put("type", "fluid")
                put("item", e.item.key)
                put("level", e.level.name)
            }
            is VehicleEvent.AirbagChanged -> buildJsonObject {
                put("type", "airbag")
                put("area", e.area.name)
                put("deployed", e.deployed)
            }
            is VehicleEvent.Note -> buildJsonObject {
                put("type", "note")
                put("note", e.type)
            }
            else -> return null
        }
        return JsonObject(fields + ("at" to JsonPrimitive(logged.at.toString())))
    }

    private fun event(type: String, json: JsonObject): VehicleEvent = when (type) {
        "telltales" -> VehicleEvent.TelltalesChanged(
            json.getValue("active").jsonArray.map { it.jsonObject.let { t -> Telltale(t.text("code"), t.text("label")) } }.toSet(),
        )
        "fluid" -> VehicleEvent.FluidChanged(
            StateItem.entries.first { it.key == json.text("item") },
            FluidLevel.valueOf(json.text("level")),
        )
        "airbag" -> VehicleEvent.AirbagChanged(Area.valueOf(json.text("area")), json.getValue("deployed").jsonPrimitive.boolean)
        "note" -> VehicleEvent.Note(json.text("note"))
        else -> throw IllegalArgumentException("모르는 줄: $type")
    }
}
