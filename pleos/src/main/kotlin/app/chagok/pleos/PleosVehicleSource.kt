package app.chagok.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pleos Playground Vehicle SDK로 읽는 [VehicleSource].
 *
 * SDK는 값을 콜백(성공, 실패)으로 돌려주므로 읽기는 CompletableDeferred로, 변화는 리스너를 callbackFlow로 감싼다.
 * 차종마다 지원 항목이 달라 처음 읽을 때 check…Capabilities로 지원 목록을 만들고(기능 정의서 F-SNP-03),
 * 지원하지 않는 항목은 [Unavailable.Unsupported], 읽다가 실패한 항목은 [Unavailable.Failed]로 남긴다.
 *
 * SDK 호출은 모두 [Dispatchers.IO]에서 한다. SDK가 호출 안에서 코틀린 리플렉션을 써서 처음 부르면 수 초씩 걸리는데,
 * 메인 스레드에서 부르면 화면 입력이 막혀 시스템이 ANR로 앱을 종료한다(2026.11.01 에뮬레이터 부팅 직후 재현).
 */
class PleosVehicleSource(
    context: Context,
    private val timeout: Duration = 2.seconds,
) : VehicleSource, AutoCloseable {
    private val vehicle = Vehicle(context.applicationContext).also { it.initialize() }
    private val supportLock = Mutex()

    @Volatile
    private var cachedSupport: Support? = null

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 첫 인수 기록이 오래 걸리지 않게 SDK 연결을 조금 기다렸다가 지원 목록을 미리 만들어 둔다.
        background.launch {
            delay(1.seconds)
            runCatching { support() }
        }
    }

    override suspend fun state(): VehicleState = withContext(Dispatchers.IO) {
        val s = support()
        fun <T> read(item: StateItem, block: Call<T>) =
            async { if (item in s.unsupported) Outcome.Fail(Unavailable.Unsupported) else call(block) }
        fun <T> perArea(item: StateItem, areas: List<Area>, block: (Area) -> Call<T>) =
            areas.associateWith { area -> async { if (item in s.unsupported) Outcome.Fail(Unavailable.Unsupported) else call(block(area)) } }

        val fuel = read<Int?>(StateItem.FUEL) { ok, fail -> vehicle.getFuel().getFuelPercent(ok, fail) }
        val soc = read<Int?>(StateItem.EV_SOC) { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
        val charging = read<Boolean?>(StateItem.EV_CHARGING) { ok, fail -> vehicle.getEvBattery().getEvChargeSwitchState(ok, fail) }
        val odometer = read<Float?>(StateItem.ODOMETER) { ok, fail -> vehicle.getOdometer().getCurrentDistanceDriven(ok, fail) }
        val sinceRefill = read<Float?>(StateItem.TRIP_SINCE_REFILL) { ok, fail ->
            vehicle.getOdometer().getTripSinceRefillDrivingDistance(ok, fail)
        }
        val headlights = read<LightSwitch?>(StateItem.HEADLIGHTS) { ok, fail -> vehicle.getLight().getLowBeamLightSwitch(ok, fail) }
        val tailLamp = read<LightSwitch?>(StateItem.TAIL_LAMP) { ok, fail -> vehicle.getLight().getTailLampLightSwitch(ok, fail) }
        val cabinLight = read<LightSwitch?>(StateItem.CABIN_LIGHT) { ok, fail -> vehicle.getLight().getCabinLightSwitch(ok, fail) }
        val engineOil = read<OilLevelState?>(StateItem.ENGINE_OIL) { ok, fail -> vehicle.getSafety().getEngineOilLevelState(ok, fail) }
        val brakeFluid = read<OilLevelState?>(StateItem.BRAKE_FLUID) { ok, fail -> vehicle.getSafety().getBrakeFluidLevelState(ok, fail) }
        val washer = read<Boolean?>(StateItem.WASHER_FLUID) { ok, fail -> vehicle.getSafety().hasLowWasherFluidWarning(ok, fail) }
        val telltales = read<Telltales?>(StateItem.TELLTALES) { ok, fail -> vehicle.getSafety().getTelltalesInfo(ok, fail) }
        val doors = perArea<Int?>(StateItem.DOORS, s.doors) { area ->
            { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(DoorArea(DOORS.getValue(area)), ok, fail) }
        }
        val windows = perArea<Int?>(StateItem.WINDOWS, s.windows) { area ->
            { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(WindowArea(WINDOWS.getValue(area)), ok, fail) }
        }
        val seats = perArea<Boolean?>(StateItem.SEATS, s.seats) { area ->
            { ok, fail -> vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(SEATS.getValue(area)), ok, fail) }
        }
        val airbags = perArea<Boolean?>(StateItem.AIRBAGS, AIRBAG_SEATS) { area ->
            { ok, fail -> vehicle.getSafety().hasSeatAirbagBeenDeployed(SeatArea(SEATS.getValue(area)), ok, fail) }
        }
        val pressures = perArea<Float?>(StateItem.TIRES, s.wheels) { area ->
            { ok, fail -> vehicle.getTire().getTirePressure(WheelArea(WHEELS.getValue(area)), ok, fail) }
        }
        // 저압 기준값. AAOS의 CRITICALLY_LOW_TIRE_PRESSURE처럼 이 값 이하를 저압으로 본다.
        val lowLimits = perArea<Float?>(StateItem.TIRES, s.wheels) { area ->
            { ok, fail -> vehicle.getTire().getLowTirePressure(WheelArea(WHEELS.getValue(area)), ok, fail) }
        }

        val unavailable = mutableMapOf<StateItem, Unavailable>()
        suspend fun <T : Any> Deferred<Outcome<T?>>.value(item: StateItem): T? = when (val o = await()) {
            is Outcome.Ok -> o.value ?: null.also { unavailable[item] = Unavailable.Failed("EMPTY") }
            is Outcome.Fail -> null.also { unavailable[item] = o.reason }
        }
        suspend fun <T : Any, R> Map<Area, Deferred<Outcome<T?>>>.values(item: StateItem, convert: (T) -> R): Map<Area, R> {
            val read = mapValues { it.value.await() }
            val values = read.mapNotNull { (area, o) -> (o as? Outcome.Ok)?.value?.let { area to convert(it) } }.toMap()
            if (values.isEmpty()) {
                unavailable[item] = read.values.filterIsInstance<Outcome.Fail>().firstOrNull()?.reason
                    ?: if (read.isEmpty()) Unavailable.Unsupported else Unavailable.Failed("EMPTY")
            }
            return values
        }
        fun level(item: StateItem, state: OilLevelState): FluidLevel? = when (state) {
            OilLevelState.NORMAL, OilLevelState.HIGH -> FluidLevel.NORMAL
            OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> FluidLevel.LOW
            else -> null.also { unavailable[item] = Unavailable.Failed("SENSOR_ERROR") }
        }

        val limits = lowLimits.mapValues { (it.value.await() as? Outcome.Ok)?.value }
        VehicleState(
            fuelPercent = fuel.value(StateItem.FUEL)?.toDouble(),
            evSocPercent = soc.value(StateItem.EV_SOC)?.toDouble(),
            evCharging = charging.value(StateItem.EV_CHARGING),
            odometerKm = odometer.value(StateItem.ODOMETER)?.toDouble(),
            tripSinceRefillKm = sinceRefill.value(StateItem.TRIP_SINCE_REFILL)?.toDouble(),
            doorsOpen = doors.values(StateItem.DOORS) { it != 0 },
            windowsOpen = windows.values(StateItem.WINDOWS) { it != 0 },
            // 스위치를 켜짐에 고정한 경우만 켜 둔 것으로 본다. 자동 모드에서 켜진 것은 꺼 달라고 할 일이 아니다.
            headlightsOn = headlights.value(StateItem.HEADLIGHTS)?.let { it == LightSwitch.ON },
            tailLampOn = tailLamp.value(StateItem.TAIL_LAMP)?.let { it == LightSwitch.ON },
            cabinLightOn = cabinLight.value(StateItem.CABIN_LIGHT)?.let { it == LightSwitch.ON },
            seatsOccupied = seats.values(StateItem.SEATS) { occupied(it) },
            engineOil = engineOil.value(StateItem.ENGINE_OIL)?.let { level(StateItem.ENGINE_OIL, it) },
            brakeFluid = brakeFluid.value(StateItem.BRAKE_FLUID)?.let { level(StateItem.BRAKE_FLUID, it) },
            washerFluid = washer.value(StateItem.WASHER_FLUID)?.let { if (it) FluidLevel.LOW else FluidLevel.NORMAL },
            telltales = telltales.value(StateItem.TELLTALES)?.warnings(),
            tires = pressures.values(StateItem.TIRES) { it }.mapValues { (area, kpa) ->
                TireState(pressureKpa = kpa.toDouble(), lowPressure = limits[area]?.let { kpa <= it })
            },
            airbagsDeployed = airbags.values(StateItem.AIRBAGS) { it },
            unavailable = unavailable,
        )
    }

    override fun changes(): Flow<VehicleEvent> = callbackFlow {
        val s = support()
        val undo = mutableListOf<() -> Unit>()
        val door = vehicle.getDoor()
        val window = vehicle.getWindow()
        val light = vehicle.getLight()
        val seat = vehicle.getSeat()
        val safety = vehicle.getSafety()

        if (StateItem.DOORS !in s.unsupported) s.doors.forEach { area ->
            val sdkArea = DoorArea(DOORS.getValue(area))
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    pos?.let { trySend(VehicleEvent.DoorChanged(area, it != 0)) }
                }
                override fun onFailed(e: Exception) {}
            }
            door.registerDoorPosition(sdkArea, listener)
            undo += { door.unregisterDoorPosition(sdkArea, listener) }
        }
        if (StateItem.WINDOWS !in s.unsupported) s.windows.forEach { area ->
            val sdkArea = WindowArea(WINDOWS.getValue(area))
            val listener = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) {
                    position?.let { trySend(VehicleEvent.WindowChanged(area, it != 0)) }
                }
                override fun onFailed(e: Exception) {}
            }
            window.registerWindowPosition(sdkArea, listener)
            undo += { window.unregisterWindowPosition(sdkArea, listener) }
        }
        fun lightListener(item: StateItem) = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) {
                state?.let { trySend(VehicleEvent.LightChanged(item, it == LightState.ON)) }
            }
            override fun onFailed(e: Exception) {}
        }
        if (StateItem.HEADLIGHTS !in s.unsupported) lightListener(StateItem.HEADLIGHTS).let {
            light.registerLowBeamLightState(it)
            undo += { light.unregisterLowBeamLightState(it) }
        }
        if (StateItem.TAIL_LAMP !in s.unsupported) lightListener(StateItem.TAIL_LAMP).let {
            light.registerTailLampLightState(it)
            undo += { light.unregisterTailLampLightState(it) }
        }
        if (StateItem.CABIN_LIGHT !in s.unsupported) lightListener(StateItem.CABIN_LIGHT).let {
            light.registerCabinLightState(it)
            undo += { light.unregisterCabinLightState(it) }
        }
        if (StateItem.SEATS !in s.unsupported) s.seats.forEach { area ->
            val sdkArea = SeatArea(SEATS.getValue(area))
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                    occupancy?.let { trySend(VehicleEvent.SeatOccupancyChanged(area, occupied(it))) }
                }
                override fun onFailed(e: Exception) {}
            }
            seat.registerSeatOccupancy(sdkArea, listener)
            undo += { seat.unregisterSeatOccupancy(sdkArea, listener) }
        }
        fun oilListener(item: StateItem) = object : OilLevelStateListener {
            override fun onOilLevelUpdated(level: OilLevelState?) {
                val fluid = when (level) {
                    OilLevelState.NORMAL, OilLevelState.HIGH -> FluidLevel.NORMAL
                    OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> FluidLevel.LOW
                    else -> null
                }
                fluid?.let { trySend(VehicleEvent.FluidChanged(item, it)) }
            }
            override fun onFailed(e: Exception) {}
        }
        oilListener(StateItem.ENGINE_OIL).let {
            safety.registerEngineOilLevelState(it)
            undo += { safety.unregisterEngineOilLevelState(it) }
        }
        oilListener(StateItem.BRAKE_FLUID).let {
            safety.registerBrakeFluidLevelState(it)
            undo += { safety.unregisterBrakeFluidLevelState(it) }
        }
        if (StateItem.WASHER_FLUID !in s.unsupported) object : LowWasherFluidWaringListener {
            override fun onLowWasherFluidWaringUpdated(state: Boolean?) {
                state?.let { trySend(VehicleEvent.FluidChanged(StateItem.WASHER_FLUID, if (it) FluidLevel.LOW else FluidLevel.NORMAL)) }
            }
            override fun onFailed(e: Exception) {}
        }.let {
            safety.registerLowWasherFluidWaringState(it)
            undo += { safety.unregisterLowWasherFluidWaringState(it) }
        }
        if (StateItem.TELLTALES !in s.unsupported) object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                telltales?.let { trySend(VehicleEvent.TelltalesChanged(it.warnings())) }
            }
            override fun onFailed(e: Exception) {}
        }.let {
            safety.registerTelltales(it)
            undo += { safety.unregisterTelltales(it) }
        }
        AIRBAG_SEATS.forEach { area ->
            val sdkArea = SeatArea(SEATS.getValue(area))
            val listener = object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(deployed: Boolean?) {
                    deployed?.let { trySend(VehicleEvent.AirbagChanged(area, it)) }
                }
                override fun onFailed(e: Exception) {}
            }
            safety.registerSeatAirbagDeployed(sdkArea, listener)
            undo += { safety.unregisterSeatAirbagDeployed(sdkArea, listener) }
        }

        awaitClose { undo.forEach { runCatching(it) } }
    }.flowOn(Dispatchers.IO)

    /**
     * 기어가 P인지. 인수·반납 화면 단계를 정차 중에만 열 때 쓴다(요구사항 SAF-01). 읽지 못하면 아무것도 내보내지 않는다.
     * SDK는 AAOS 표준 GEAR_SELECTION 속성을 권하지만, 그러려면 Car API 라이브러리와 권한이 더 필요해서 R1은 이 API를 쓴다.
     */
    @Suppress("DEPRECATION")
    fun parked(): Flow<Boolean> = callbackFlow {
        val gear = vehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                gear?.let { trySend(it == VehicleGear.GEAR_PARK) }
            }
            override fun onFailed(e: Exception) {}
        }
        gear.registerGearSelection(listener)
        (call<VehicleGear?> { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) } as? Outcome.Ok)?.value
            ?.let { trySend(it == VehicleGear.GEAR_PARK) }
        awaitClose { gear.unregisterGearSelection(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    override fun close() {
        background.cancel()
        vehicle.release()
    }

    private suspend fun support(): Support =
        cachedSupport ?: supportLock.withLock { cachedSupport ?: discover().also { if (it.reliable) cachedSupport = it } }

    private suspend fun discover(): Support = coroutineScope {
        val scalars = listOf(
            scalar(StateItem.FUEL) { ok, fail -> vehicle.getFuel().checkFuelPercentCapabilities(ok, fail) },
            scalar(StateItem.EV_SOC) { ok, fail -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) },
            scalar(StateItem.EV_CHARGING) { ok, fail -> vehicle.getEvBattery().checkEvChargeSwitchCapabilities(ok, fail) },
            scalar(StateItem.ODOMETER) { ok, fail -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, fail) },
            scalar(StateItem.TRIP_SINCE_REFILL) { ok, fail ->
                vehicle.getOdometer().checkTripSinceRefillDrivingDistanceCapabilities(ok, fail)
            },
            scalar(StateItem.WASHER_FLUID) { ok, fail -> vehicle.getSafety().checkLowWasherFluidWarningStateCapabilities(ok, fail) },
            scalar(StateItem.TELLTALES) { ok, fail -> vehicle.getSafety().checkTelltalesCapabilities(ok, fail) },
            switches(StateItem.HEADLIGHTS) { ok, fail -> vehicle.getLight().checkLowBeamLightSwitchCapabilities(ok, fail) },
            switches(StateItem.TAIL_LAMP) { ok, fail -> vehicle.getLight().checkTailLampLightSwitchCapabilities(ok, fail) },
            switches(StateItem.CABIN_LIGHT) { ok, fail -> vehicle.getLight().checkCabinLightSwitchCapabilities(ok, fail) },
        )
        val doors = async {
            capability<Map<DoorArea, *>> { ok, fail -> vehicle.getDoor().checkDoorPositionCapabilities(ok, fail) }
                .areas(DOORS) { map -> map.keys.flatMap { it.values.toList() } }
        }
        val windows = async {
            capability<Map<WindowArea, *>> { ok, fail -> vehicle.getWindow().checkWindowPositionCapabilities(ok, fail) }
                .areas(WINDOWS) { map -> map.keys.flatMap { it.values.toList() } }
        }
        val seats = async {
            capability<List<SeatArea>> { ok, fail -> vehicle.getSeat().checkSeatOccupancyCapabilities(ok, fail) }
                .areas(SEATS) { list -> list.flatMap { it.values.toList() } }
        }
        val wheels = async {
            capability<List<WheelArea>> { ok, fail -> vehicle.getTire().checkTirePressureCapabilities(ok, fail) }
                .areas(WHEELS) { list -> list.flatMap { it.values.toList() } }
        }
        val checked = scalars.map { it.await() }
        val areaLists = mapOf(
            StateItem.DOORS to doors.await(), StateItem.WINDOWS to windows.await(),
            StateItem.SEATS to seats.await(), StateItem.TIRES to wheels.await(),
        )
        Support(
            unsupported = checked.mapNotNull { (item, ok) -> item.takeIf { ok == false } }.toSet() +
                areaLists.filterValues { it.areas.isEmpty() }.keys,
            doors = areaLists.getValue(StateItem.DOORS).areas,
            windows = areaLists.getValue(StateItem.WINDOWS).areas,
            seats = areaLists.getValue(StateItem.SEATS).areas,
            wheels = areaLists.getValue(StateItem.TIRES).areas,
            // 확인이 하나도 성공하지 못했으면 SDK에 아직 연결되지 않은 것이라 다음에 다시 확인한다.
            reliable = checked.any { it.second != null } || areaLists.values.any { it.answered },
        )
    }

    // 지원하면 true, 지원하지 않으면 false, 확인하지 못했으면 null
    private fun CoroutineScope.scalar(item: StateItem, block: Call<CapabilityConfig?>) = async {
        item to when (val o = call(block)) {
            is Outcome.Ok -> o.value?.accessType != AccessType.NONE
            is Outcome.Fail -> if (o.reason == Unavailable.Unsupported) false else null
        }
    }

    private fun CoroutineScope.switches(item: StateItem, block: Capability<List<LightSwitch>>) = async {
        item to when (val o = capability(block)) {
            is Outcome.Ok -> o.value.first?.accessType != AccessType.NONE && o.value.second?.isNotEmpty() != false
            is Outcome.Fail -> if (o.reason == Unavailable.Unsupported) false else null
        }
    }

    // 확인하지 못했으면 일단 모든 위치를 읽어 본다.
    private fun <K, V> Outcome<Pair<CapabilityConfig?, K?>>.areas(table: Map<Area, V>, values: (K) -> List<V>): AreaSupport =
        when (this) {
            is Outcome.Ok -> {
                val (config, found) = value
                val areas = when {
                    config?.accessType == AccessType.NONE -> emptyList()
                    found == null -> table.keys.toList()
                    else -> values(found).let { sdk -> table.filterValues { it in sdk }.keys.toList() }
                }
                AreaSupport(areas, answered = true)
            }
            is Outcome.Fail -> AreaSupport(
                if (reason == Unavailable.Unsupported) emptyList() else table.keys.toList(),
                answered = reason == Unavailable.Unsupported,
            )
        }

    private suspend fun <K> capability(block: Capability<K>): Outcome<Pair<CapabilityConfig?, K?>> =
        call { ok, fail -> block({ config, value -> ok(config to value) }, fail) }

    // 시간 제한은 호출이 끝난 뒤 콜백을 기다리는 시간만 잰다. 호출 자체(리플렉션 포함)까지 재면
    // 차가 바쁠 때 값이 오는데도 '읽지 못함'으로 남아, 다시 찍을 수 없는 기록에 빈칸이 생긴다.
    private suspend fun <T> call(block: Call<T>): Outcome<T> {
        val result = CompletableDeferred<Outcome<T>>()
        try {
            block({ result.complete(Outcome.Ok(it)) }, { result.complete(Outcome.Fail(reasonOf(it))) })
        } catch (e: Exception) {
            return Outcome.Fail(reasonOf(e))
        }
        return withTimeoutOrNull(timeout) { result.await() } ?: Outcome.Fail(Unavailable.Failed("TIMEOUT"))
    }

    private class Support(
        val unsupported: Set<StateItem>,
        val doors: List<Area>,
        val windows: List<Area>,
        val seats: List<Area>,
        val wheels: List<Area>,
        val reliable: Boolean,
    )

    private class AreaSupport(val areas: List<Area>, val answered: Boolean)
}

private typealias Call<T> = (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit

/** check…Capabilities의 새 형식. 접근 권한(CapabilityConfig)과 지원 위치·값을 함께 준다. */
private typealias Capability<K> = (onComplete: (CapabilityConfig?, K?) -> Unit, onFailed: (Exception) -> Unit) -> Unit

private sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Fail(val reason: Unavailable) : Outcome<Nothing>
}

// 2열 차량 기준. 3열과 후드·트렁크(DoorValue.HOOD, REAR), 선루프(ROOF_TOP)는 아직 반납 체크 항목에 넣지 않았다.
private val DOORS = mapOf(
    Area.FRONT_LEFT to DoorValue.ROW_1_LEFT, Area.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
    Area.REAR_LEFT to DoorValue.ROW_2_LEFT, Area.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
)
private val WINDOWS = mapOf(
    Area.FRONT_LEFT to WindowValue.ROW_1_LEFT, Area.FRONT_RIGHT to WindowValue.ROW_1_RIGHT,
    Area.REAR_LEFT to WindowValue.ROW_2_LEFT, Area.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
)
private val SEATS = mapOf(
    Area.FRONT_LEFT to SeatValue.ROW_1_LEFT, Area.FRONT_RIGHT to SeatValue.ROW_1_RIGHT,
    Area.REAR_LEFT to SeatValue.ROW_2_LEFT, Area.REAR_CENTER to SeatValue.ROW_2_CENTER, Area.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
)
private val WHEELS = mapOf(
    Area.FRONT_LEFT to WheelValue.LEFT_FRONT, Area.FRONT_RIGHT to WheelValue.RIGHT_FRONT,
    Area.REAR_LEFT to WheelValue.LEFT_REAR, Area.REAR_RIGHT to WheelValue.RIGHT_REAR,
)
private val AIRBAG_SEATS = listOf(Area.FRONT_LEFT, Area.FRONT_RIGHT)

// SDK 2.3.2의 착석 값은 AAOS와 반대로 온다. 에뮬레이터에서 SEAT_OCCUPANCY가 VACANT(1)이면 true, OCCUPIED(2)이면 false였다
// (2026.11.01, Virtual Console로 확인). 실차에서 다시 확인해야 한다(요구사항 미결 Q7).
private fun occupied(sdkValue: Boolean) = !sdkValue

private val ERROR_CODES = listOf(
    "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE",
    "ERROR_SECURITY_PERMISSION", "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE",
    "ERROR_DRIVING_RESTRICTED", "ERROR_EMPTY_RESULT", "ERROR_UNDEFINED",
)

/** SDK 예외의 오류 코드로 "지원 안 함"과 "읽지 못함"을 나눈다. */
internal fun reasonOf(e: Throwable): Unavailable = when (val code = errorCodeOf(e)) {
    "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE" -> Unavailable.Unsupported
    "ERROR_SECURITY_PERMISSION" -> Unavailable.Failed("PERMISSION")
    "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> Unavailable.Failed("SDK_ERROR")
    "ERROR_DRIVING_RESTRICTED" -> Unavailable.Failed("DRIVING_RESTRICTED")
    "ERROR_EMPTY_RESULT" -> Unavailable.Failed("EMPTY")
    else -> Unavailable.Failed(code ?: e.javaClass.simpleName)
}

// SDK 2.3.2의 예외 클래스와 메서드 이름은 난독화돼 있어(callback.exception.a.a()) 반환 형식(ErrorCode)으로 찾는다.
// 권한이 모자라면 SDK가 "need Permission"만 남기고 오류 코드를 주지 않을 때가 있어 메시지와 예외 종류도 본다.
private fun errorCodeOf(e: Throwable): String? {
    val getter = e.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType.simpleName == "ErrorCode" }
    return runCatching { (getter?.invoke(e) as? Enum<*>)?.name }.getOrNull()
        ?: ERROR_CODES.firstOrNull { e.message?.contains(it) == true }
        ?: "ERROR_SECURITY_PERMISSION".takeIf { e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true }
}
