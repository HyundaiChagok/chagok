package app.chagok.core.record

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnapshotRecordTest {
    private val items = setOf(
        StateItem.FUEL, StateItem.DOORS, StateItem.TELLTALES, StateItem.TIRES, StateItem.CABIN_LIGHT, StateItem.ENGINE_OIL,
    )

    private val state = VehicleState(
        fuelPercent = 82.0,
        doorsOpen = mapOf(Area.FRONT_LEFT to false, Area.FRONT_RIGHT to false),
        telltales = setOf(Telltale("TPMS", "타이어 공기압")),
        tires = mapOf(Area.FRONT_LEFT to TireState(lowPressure = false)),
        unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported),
    )

    private fun record(state: VehicleState) = SnapshotRecord(
        sessionId = "s-1", recordNo = "0007-1103-A1", kind = SnapshotKind.PICKUP, vehicleId = "V-0007",
        capturedAt = Instant.parse("2026-11-03T00:12:00Z"), state = state.forRecord(items), items = items,
        appVersion = "0.1.0", rulesVersion = "rental/1",
    )

    @Test
    fun `JSON으로 저장했다가 그대로 되살린다`() {
        val original = record(state)
        val text = Canonical.encode(original.toJsonWithHash())
        assertEquals(original, SnapshotRecord.fromJson(Json.parseToJsonElement(text).jsonObject))
    }

    @Test
    fun `항목마다 세 가지 상태 가운데 하나로 남긴다`() {
        val readings = record(state).toJson().getValue("readings").jsonObject
        assertEquals("""{"status":"OK","value":82.0}""", Canonical.encode(readings.getValue("fuelPercent")))
        assertEquals("""{"status":"UNSUPPORTED"}""", Canonical.encode(readings.getValue("cabinLightOn")))
        assertEquals("""{"reason":"NOT_READ","status":"FAILED"}""", Canonical.encode(readings.getValue("engineOil")))
        assertEquals(items.map { it.key }.toSet(), readings.keys)
    }

    @Test
    fun `무결성 값은 넣은 순서와 상관없고, 값이 하나만 바뀌어도 달라진다`() {
        val a = record(state)
        val b = record(state.copy(doorsOpen = mapOf(Area.FRONT_RIGHT to false, Area.FRONT_LEFT to false)))
        assertEquals(a.hash, b.hash)
        assertNotEquals(a.hash, record(state.copy(fuelPercent = 81.0)).hash)
        assertTrue(a.hash.matches(Regex("sha256:[0-9a-f]{64}")), a.hash)
        assertEquals(a.hash.substring(7, 11) + " " + a.hash.substring(11, 15), Canonical.short(a.hash))
    }

    @Test
    fun `내용과 맞지 않는 무결성 값은 받지 않는다`() {
        val json = record(state).toJsonWithHash()
        val tampered = JsonObject(json + ("recordNo" to JsonPrimitive("0007-1103-A2")))
        assertFailsWith<IllegalArgumentException> { SnapshotRecord.fromJson(tampered) }
    }
}
