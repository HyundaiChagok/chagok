package app.chagok.core.record

import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [VehicleState]의 기록 형식. 항목마다 status가 OK·UNSUPPORTED·FAILED 가운데 하나라서 빈칸이 없다.
 *
 * ```
 * "fuelPercent": {"status": "OK", "value": 61.0}
 * "cabinLightOn": {"status": "UNSUPPORTED"}
 * "brakeFluid": {"status": "FAILED", "reason": "TIMEOUT"}
 * ```
 */
object StateJson {
    fun encode(state: VehicleState, items: Set<StateItem>): JsonObject = buildJsonObject {
        StateItem.entries.filter { it in items }.forEach { put(it.key, reading(state, it)) }
    }

    /** [encode]의 반대. 들어 있지 않은 항목은 기록하지 않은 것이므로 그대로 비워 둔다. */
    fun decode(readings: JsonObject): VehicleState {
        var state = VehicleState()
        val unavailable = mutableMapOf<StateItem, Unavailable>()
        for (item in StateItem.entries) {
            val reading = readings[item.key]?.jsonObject ?: continue
            when (reading.text("status")) {
                "OK" -> state = state.withValue(item, reading.getValue("value"))
                "UNSUPPORTED" -> unavailable[item] = Unavailable.Unsupported
                else -> unavailable[item] = Unavailable.Failed(reading["reason"]?.jsonPrimitive?.content ?: VehicleState.NOT_READ)
            }
        }
        return state.copy(unavailable = unavailable)
    }

    private fun reading(state: VehicleState, item: StateItem): JsonObject = buildJsonObject {
        when (val missing = state.missing(item)) {
            null -> {
                put("status", "OK")
                put("value", value(state, item))
            }
            Unavailable.Unsupported -> put("status", "UNSUPPORTED")
            is Unavailable.Failed -> {
                put("status", "FAILED")
                put("reason", missing.reason)
            }
        }
    }

    private fun value(state: VehicleState, item: StateItem): JsonElement = when (item) {
        StateItem.FUEL -> JsonPrimitive(state.fuelPercent)
        StateItem.EV_SOC -> JsonPrimitive(state.evSocPercent)
        StateItem.EV_CHARGING -> JsonPrimitive(state.evCharging)
        StateItem.EV_CAPACITY -> JsonPrimitive(state.evCapacityKwh)
        StateItem.ODOMETER -> JsonPrimitive(state.odometerKm)
        StateItem.TRIP_SINCE_REFILL -> JsonPrimitive(state.tripSinceRefillKm)
        StateItem.DOORS -> areas(state.doorsOpen)
        StateItem.DOOR_LOCKS -> areas(state.doorsLocked)
        StateItem.WINDOWS -> areas(state.windowsOpen)
        StateItem.TRUNK -> JsonPrimitive(state.trunkOpen)
        StateItem.CHARGE_PORT -> JsonPrimitive(state.chargePortOpen)
        StateItem.HEADLIGHTS -> JsonPrimitive(state.headlightsOn)
        StateItem.TAIL_LAMP -> JsonPrimitive(state.tailLampOn)
        StateItem.CABIN_LIGHT -> JsonPrimitive(state.cabinLightOn)
        StateItem.PARKING_BRAKE -> JsonPrimitive(state.parkingBrakeEngaged)
        StateItem.SEATS -> areas(state.seatsOccupied)
        StateItem.ENGINE_OIL -> JsonPrimitive(state.engineOil?.name)
        StateItem.BRAKE_FLUID -> JsonPrimitive(state.brakeFluid?.name)
        StateItem.WASHER_FLUID -> JsonPrimitive(state.washerFluid?.name)
        StateItem.TELLTALES -> JsonArray(
            state.telltales.orEmpty().sortedBy { it.code }.map { buildJsonObject { put("code", it.code); put("label", it.label) } },
        )
        StateItem.TIRES -> JsonObject(
            state.tires.entries.associate { (area, tire) ->
                area.name to buildJsonObject {
                    tire.lowPressure?.let { put("low", it) }
                    tire.pressureKpa?.let { put("kpa", it) }
                }
            },
        )
        StateItem.AIRBAGS -> areas(state.airbagsDeployed)
    }

    private fun VehicleState.withValue(item: StateItem, value: JsonElement): VehicleState = when (item) {
        StateItem.FUEL -> copy(fuelPercent = value.jsonPrimitive.double)
        StateItem.EV_SOC -> copy(evSocPercent = value.jsonPrimitive.double)
        StateItem.EV_CHARGING -> copy(evCharging = value.jsonPrimitive.boolean)
        StateItem.EV_CAPACITY -> copy(evCapacityKwh = value.jsonPrimitive.double)
        StateItem.ODOMETER -> copy(odometerKm = value.jsonPrimitive.double)
        StateItem.TRIP_SINCE_REFILL -> copy(tripSinceRefillKm = value.jsonPrimitive.double)
        StateItem.DOORS -> copy(doorsOpen = areasOf(value))
        StateItem.DOOR_LOCKS -> copy(doorsLocked = areasOf(value))
        StateItem.WINDOWS -> copy(windowsOpen = areasOf(value))
        StateItem.TRUNK -> copy(trunkOpen = value.jsonPrimitive.boolean)
        StateItem.CHARGE_PORT -> copy(chargePortOpen = value.jsonPrimitive.boolean)
        StateItem.HEADLIGHTS -> copy(headlightsOn = value.jsonPrimitive.boolean)
        StateItem.TAIL_LAMP -> copy(tailLampOn = value.jsonPrimitive.boolean)
        StateItem.CABIN_LIGHT -> copy(cabinLightOn = value.jsonPrimitive.boolean)
        StateItem.PARKING_BRAKE -> copy(parkingBrakeEngaged = value.jsonPrimitive.boolean)
        StateItem.SEATS -> copy(seatsOccupied = areasOf(value))
        StateItem.ENGINE_OIL -> copy(engineOil = FluidLevel.valueOf(value.jsonPrimitive.content))
        StateItem.BRAKE_FLUID -> copy(brakeFluid = FluidLevel.valueOf(value.jsonPrimitive.content))
        StateItem.WASHER_FLUID -> copy(washerFluid = FluidLevel.valueOf(value.jsonPrimitive.content))
        StateItem.TELLTALES -> copy(
            telltales = value.jsonArray.map { it.jsonObject.let { t -> Telltale(t.text("code"), t.text("label")) } }.toSet(),
        )
        StateItem.TIRES -> copy(
            tires = value.jsonObject.entries.associate { (area, tire) ->
                Area.valueOf(area) to TireState(
                    pressureKpa = tire.jsonObject["kpa"]?.jsonPrimitive?.double,
                    lowPressure = tire.jsonObject["low"]?.jsonPrimitive?.boolean,
                )
            },
        )
        StateItem.AIRBAGS -> copy(airbagsDeployed = areasOf(value))
    }

    private fun areas(map: Map<Area, Boolean>): JsonObject =
        JsonObject(map.entries.associate { (area, value) -> area.name to JsonPrimitive(value) })

    private fun areasOf(value: JsonElement): Map<Area, Boolean> =
        value.jsonObject.entries.associate { (area, flag) -> Area.valueOf(area) to flag.jsonPrimitive.boolean }
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
