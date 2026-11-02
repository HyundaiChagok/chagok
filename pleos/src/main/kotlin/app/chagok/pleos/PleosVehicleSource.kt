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
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.StateItem
import app.chagok.core.model.TireState
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.model.Vin
import app.chagok.core.model.item
import app.chagok.core.rental.Rental
import app.chagok.core.source.AreaReading
import app.chagok.core.source.AreaValues
import app.chagok.core.source.VehicleSource
import app.chagok.core.source.readAreas
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
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
 *
 * 에뮬레이터(전기차)에서 실제로 온 값(2026.11.02): 배터리·주행거리·문·창문·트렁크·충전구·라이트·경고등·좌석·배터리 용량은 읽히고,
 * 연료·주유 후 거리·엔진오일·브레이크액·워셔액·에어백·타이어 공기압은 차량 서비스에 속성이 없어 지원 안 함으로 온다.
 *
 * SDK 리스너 해제의 함정(2026.11.02 에뮬레이터에서 확인): Vehicle 객체 하나에서 리스너를 하나 해제하면 항목이 달라도 그 객체의 리스너가 모두 끊기고,
 * 같은 항목이면 다른 Vehicle 객체의 리스너까지 끊긴다. 오류는 없고 값만 오지 않는다. 반납 체크를 닫을 때 해제했더니 대여 중 기록과 기어 리스너가 조용히 죽었다.
 * 그래서 함께 걸고 함께 푸는 묶음마다 Vehicle 객체를 따로 두고 항목이 겹치지 않게 한다. 대여 중 기록은 경고등·소모품·에어백([recordingEvents]),
 * 반납 체크 화면은 나머지([screenEvents]), 기어는 따로([parked]). 묶음은 구독자가 모두 떠날 때만 한꺼번에 푼다.
 */
class PleosVehicleSource(
    context: Context,
    private val timeout: Duration = 2.seconds,
) : VehicleSource, AutoCloseable {
    /** 읽기, 지원 확인, 대여 중 기록의 리스너 */
    private val vehicle = Vehicle(context.applicationContext).also { it.initialize() }

    /** 반납 체크 화면이 떠 있는 동안의 리스너 */
    private val screenVehicle = Vehicle(context.applicationContext).also { it.initialize() }

    /** 기어 리스너 */
    private val gearVehicle = Vehicle(context.applicationContext).also { it.initialize() }

    private val supportLock = Mutex()

    @Volatile
    private var cachedSupport: Support? = null

    @Volatile
    private var cachedVin: String? = null

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 앱이 뒤로 갔을 때 읽기가 멈췄는지 로그로 보려고 센다.
    private var reads = 0

    // 항목마다 SDK가 마지막으로 값이나 실패를 알려 온 시각. 시간 초과는 콜백이 온 것이 아니므로 적지 않는다.
    private val callbacks = ConcurrentHashMap<StateItem, Instant>()

    private val recordingEvents = shared(vehicle, Rental.WATCHED_DURING_RENTAL)

    // 반납 체크는 시작할 때 차를 읽으므로 리스너가 처음 주는 현재 값은 필요 없다. 리스너마다 하나씩 오는 값으로 차를 수십 번 다시 읽지 않게 버린다.
    private val screenEvents = shared(screenVehicle, StateItem.entries.toSet() - Rental.WATCHED_DURING_RENTAL, dropFirst = true)

    // 화면이 잠깐 다시 만들어지는 동안에는 풀지 않는다. 풀었다 걸기가 겹쳐 새 리스너가 끊기는 일을 막는다.
    private val parkedEvents: Flow<Boolean> = gearEvents().shareIn(background, SharingStarted.WhileSubscribed(5_000, 0), replay = 1)

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
            async { if (item in s.unsupported) Outcome.Fail(Unavailable.Unsupported) else call(block.noted(item)) }
        fun <T : Any> perArea(item: StateItem, areas: List<Area>, block: (Area) -> Call<T?>) = async {
            if (item in s.unsupported) {
                AreaValues<T>(emptyMap(), Unavailable.Unsupported)
            } else {
                readAreas(areas) { area ->
                    when (val o = call(block(area).noted(item))) {
                        is Outcome.Ok -> o.value?.let { AreaReading.Value(it) } ?: AreaReading.Missing(Unavailable.Failed("EMPTY"))
                        is Outcome.Fail -> AreaReading.Missing(o.reason)
                    }
                }
            }
        }

        val fuel = read<Int?>(StateItem.FUEL) { ok, fail -> vehicle.getFuel().getFuelPercent(ok, fail) }
        val soc = read<Int?>(StateItem.EV_SOC) { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
        val charging = read<Boolean?>(StateItem.EV_CHARGING) { ok, fail -> vehicle.getEvBattery().getEvChargeSwitchState(ok, fail) }
        // 문서 목록에는 없고 컴파일과 에뮬레이터로 확인한 API다. 콘솔에 넣은 64kWh가 64000.0으로 읽혀 Wh 단위다(AAOS INFO_EV_BATTERY_CAPACITY와 같다).
        val capacity = read<Float?>(StateItem.EV_CAPACITY) { ok, fail -> vehicle.getEvBattery().getEvBatteryCapacity(ok, fail) }
        // 충전구 읽기와 구독도 문서 목록에 없고 컴파일과 에뮬레이터로 확인했다.
        val chargePort = read<Boolean?>(StateItem.CHARGE_PORT) { ok, fail -> vehicle.getEvBattery().isEvChargePortOpen(ok, fail) }
        val trunk = read<Int?>(StateItem.TRUNK) { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.REAR), ok, fail) }
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
        val doors = perArea<Int>(StateItem.DOORS, s.doors) { area ->
            { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(DoorArea(DOORS.getValue(area)), ok, fail) }
        }
        val windows = perArea<Int>(StateItem.WINDOWS, s.windows) { area ->
            { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(WindowArea(WINDOWS.getValue(area)), ok, fail) }
        }
        val seats = perArea<Boolean>(StateItem.SEATS, s.seats) { area ->
            { ok, fail -> vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(SEATS.getValue(area)), ok, fail) }
        }
        val airbags = perArea<Boolean>(StateItem.AIRBAGS, s.airbags) { area ->
            { ok, fail -> vehicle.getSafety().hasSeatAirbagBeenDeployed(SeatArea(SEATS.getValue(area)), ok, fail) }
        }
        val pressures = perArea<Float>(StateItem.TIRES, s.wheels) { area ->
            { ok, fail -> vehicle.getTire().getTirePressure(WheelArea(WHEELS.getValue(area)), ok, fail) }
        }
        // 저압 기준값. AAOS의 CRITICALLY_LOW_TIRE_PRESSURE처럼 이 값 이하를 저압으로 본다.
        val lowLimits = perArea<Float>(StateItem.TIRES, s.wheels) { area ->
            { ok, fail -> vehicle.getTire().getLowTirePressure(WheelArea(WHEELS.getValue(area)), ok, fail) }
        }

        val unavailable = mutableMapOf<StateItem, Unavailable>()
        suspend fun <T : Any> Deferred<Outcome<T?>>.value(item: StateItem): T? = when (val o = await()) {
            is Outcome.Ok -> o.value ?: null.also { unavailable[item] = Unavailable.Failed("EMPTY") }
            is Outcome.Fail -> null.also { unavailable[item] = o.reason }
        }
        // 한 자리라도 끝내 못 읽으면 readAreas가 값을 비우고 항목 전체를 실패로 준다. 나머지 자리로 '모두 닫힘'이 되지 않는다.
        suspend fun <T : Any, R> Deferred<AreaValues<T>>.values(item: StateItem, convert: (T) -> R): Map<Area, R> {
            val read = await()
            read.missing?.let { unavailable[item] = it }
            return read.values.mapValues { convert(it.value) }
        }
        fun level(item: StateItem, state: OilLevelState): FluidLevel? = when (state) {
            OilLevelState.NORMAL, OilLevelState.HIGH -> FluidLevel.NORMAL
            OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> FluidLevel.LOW
            else -> null.also { unavailable[item] = Unavailable.Failed("SENSOR_ERROR") }
        }

        // 기준값을 못 읽으면 저압인지 알 수 없다. 공기압 값만으로 '저압 없음'이 되지 않게 타이어 항목 전체를 실패로 둔다.
        // 기준값을 지원하지 않는 차는 공기압만 남긴다(lowPressure = null).
        val limits = lowLimits.await()
        val limitFailure = limits.missing as? Unavailable.Failed
        val tires = pressures.values(StateItem.TIRES) { it }.takeUnless { limitFailure != null }.orEmpty().mapValues { (area, kpa) ->
            TireState(pressureKpa = kpa.toDouble(), lowPressure = limits.values[area]?.let { kpa <= it })
        }
        if (limitFailure != null) unavailable[StateItem.TIRES] = limitFailure
        fun <T : Any> T.inRange(item: StateItem, ok: (T) -> Boolean): T? =
            takeIf(ok) ?: null.also { unavailable[item] = Unavailable.Failed("OUT_OF_RANGE") }
        val fuelPercent = fuel.value(StateItem.FUEL)?.inRange(StateItem.FUEL) { it in 0..100 }
        val socPercent = soc.value(StateItem.EV_SOC)?.inRange(StateItem.EV_SOC) { it in 0..100 }
        // 전기차에는 엔진오일이 없다. 값이 오더라도(센서 오류 포함) 칸을 만들지 않는다.
        val electric = socPercent != null && unavailable[StateItem.FUEL] == Unavailable.Unsupported
        if (electric) unavailable[StateItem.ENGINE_OIL] = Unavailable.Unsupported
        Log.d(TAG, "차 상태 읽음 #${++reads}")
        VehicleState(
            fuelPercent = fuelPercent?.toDouble(),
            evSocPercent = socPercent?.toDouble(),
            evCharging = charging.value(StateItem.EV_CHARGING),
            evCapacityKwh = capacity.value(StateItem.EV_CAPACITY)?.let { (it / 1000.0).oneDecimal() }?.inRange(StateItem.EV_CAPACITY) { it in 5.0..300.0 },
            odometerKm = odometer.value(StateItem.ODOMETER)?.toDouble()?.oneDecimal(),
            tripSinceRefillKm = sinceRefill.value(StateItem.TRIP_SINCE_REFILL)?.toDouble()?.oneDecimal(),
            doorsOpen = doors.values(StateItem.DOORS) { it != 0 },
            windowsOpen = windows.values(StateItem.WINDOWS) { it != 0 },
            trunkOpen = trunk.value(StateItem.TRUNK)?.let { it != 0 },
            chargePortOpen = chargePort.value(StateItem.CHARGE_PORT),
            // 스위치를 켜짐에 고정한 경우만 켜 둔 것으로 본다. 자동 모드에서 켜진 것은 꺼 달라고 할 일이 아니다.
            headlightsOn = headlights.value(StateItem.HEADLIGHTS)?.let { it == LightSwitch.ON },
            tailLampOn = tailLamp.value(StateItem.TAIL_LAMP)?.let { it == LightSwitch.ON },
            cabinLightOn = cabinLight.value(StateItem.CABIN_LIGHT)?.let { it == LightSwitch.ON },
            seatsOccupied = seats.values(StateItem.SEATS) { occupied(it) },
            engineOil = if (electric) null else engineOil.value(StateItem.ENGINE_OIL)?.let { level(StateItem.ENGINE_OIL, it) },
            brakeFluid = brakeFluid.value(StateItem.BRAKE_FLUID)?.let { level(StateItem.BRAKE_FLUID, it) },
            washerFluid = washer.value(StateItem.WASHER_FLUID)?.let { if (it) FluidLevel.LOW else FluidLevel.NORMAL },
            telltales = telltales.value(StateItem.TELLTALES)?.warnings(),
            tires = tires,
            airbagsDeployed = airbags.values(StateItem.AIRBAGS) { it },
            unavailable = unavailable,
        )
    }

    override fun lastCallbacks(): Map<StateItem, Instant> = callbacks.toMap()

    // 값이 그대로여도 리스너가 불렸으면 콜백이 온 것이다. 그래서 같은 값을 거르기 전에 적는다.
    private fun heard(item: StateItem?) {
        item?.let { callbacks[it] = Instant.now() }
    }

    private fun <T> Call<T>.noted(item: StateItem): Call<T> = { ok, fail ->
        this({ heard(item); ok(it) }, { heard(item); fail(it) })
    }

    override fun changes(): Flow<VehicleEvent> = merge(recordingEvents.live(), screenEvents.live())

    /** 대여 중 기록은 경고등·소모품·에어백 묶음만 듣는다. 문·잔량·주행거리 리스너는 반납 체크 화면이 떠 있을 때만 걸린다. */
    override fun changes(items: Set<StateItem>): Flow<VehicleEvent> = when {
        items.all { it in Rental.WATCHED_DURING_RENTAL } -> recordingEvents.live()
        items.none { it in Rental.WATCHED_DURING_RENTAL } -> screenEvents.live()
        else -> changes()
    }.filter { it.item in items }

    override suspend fun vin(): String? = cachedVin ?: withContext(Dispatchers.IO) {
        val read = call<String?> { ok, fail -> vehicle.getCarInfo().getVINInfo(ok, fail) }
        Vin.parse((read as? Outcome.Ok)?.value).also {
            cachedVin = it
            // 번호 자체는 로그에 남기지 않는다.
            if (it == null) Log.i(TAG, "차대번호를 읽지 못해 업체 등록 번호로 대신합니다")
        }
    }

    // 구독자가 생기면 [items]의 리스너를 모두 걸고 마지막 구독자가 떠나면 모두 푼다. 구독자마다 따로 걸면 한쪽이 풀 때 다른 쪽이 끊긴다.
    // 끊긴 사실은 값으로 흘려보낸다. shareIn은 예외를 구독자에게 주지 않고 앱을 죽인다.
    private fun shared(owner: Vehicle, items: Set<StateItem>, dropFirst: Boolean = false): Flow<Result<VehicleEvent>> = subscribe(owner, items)
        .onEach { heard(it.item) }
        .onlyChanges(dropFirst)
        .onEach { Log.d(TAG, "변화: ${it.item}") }
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .shareIn(background, SharingStarted.WhileSubscribed(), replay = 0)

    private fun Flow<Result<VehicleEvent>>.live(): Flow<VehicleEvent> = map { it.getOrThrow() }

    private fun subscribe(owner: Vehicle, items: Set<StateItem>): Flow<VehicleEvent> = callbackFlow {
        val s = connectedSupport()
        val undo = mutableListOf<() -> Unit>()
        val door = owner.getDoor()
        val window = owner.getWindow()
        val light = owner.getLight()
        val seat = owner.getSeat()
        val safety = owner.getSafety()
        val battery = owner.getEvBattery()
        val heard = mutableSetOf<StateItem>()
        fun wanted(item: StateItem) = (item in items && item !in s.unsupported).also { if (it) heard += item }

        // 리스너가 실패를 알리면 값이 더 오지 않는다. 구독을 끝내 부르는 쪽이 끊긴 구간으로 남기고 다시 구독하게 한다.
        fun lost(item: StateItem, e: Exception) {
            Log.w(TAG, "구독 실패: $item ${e.message}")
            close(e)
        }

        if (wanted(StateItem.DOORS)) s.doors.forEach { area ->
            val sdkArea = DoorArea(DOORS.getValue(area))
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    pos?.let { trySend(VehicleEvent.DoorChanged(area, it != 0)) }
                }
                override fun onFailed(e: Exception) = lost(StateItem.DOORS, e)
            }
            door.registerDoorPosition(sdkArea, listener)
            undo += { door.unregisterDoorPosition(sdkArea, listener) }
        }
        if (wanted(StateItem.TRUNK)) {
            val sdkArea = DoorArea(DoorValue.REAR)
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    pos?.let { trySend(VehicleEvent.ClosureChanged(StateItem.TRUNK, it != 0)) }
                }
                override fun onFailed(e: Exception) = lost(StateItem.TRUNK, e)
            }
            door.registerDoorPosition(sdkArea, listener)
            undo += { door.unregisterDoorPosition(sdkArea, listener) }
        }
        if (wanted(StateItem.CHARGE_PORT)) object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(isOpened: Boolean?) {
                isOpened?.let { trySend(VehicleEvent.ClosureChanged(StateItem.CHARGE_PORT, it)) }
            }
            override fun onFailed(e: Exception) = lost(StateItem.CHARGE_PORT, e)
        }.let {
            battery.registerEvChargePortOpen(it)
            undo += { battery.unregisterEvChargePortOpen(it) }
        }
        if (wanted(StateItem.EV_SOC)) object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                value?.let { trySend(VehicleEvent.LevelChanged(StateItem.EV_SOC, it.toDouble())) }
            }
            override fun onFailed(e: Exception) = lost(StateItem.EV_SOC, e)
        }.let {
            battery.registerEvBatteryStateOfCharge(it)
            undo += { battery.unregisterEvBatteryStateOfCharge(it) }
        }
        if (wanted(StateItem.ODOMETER)) object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                value?.let { trySend(VehicleEvent.LevelChanged(StateItem.ODOMETER, it.toDouble().oneDecimal())) }
            }
            override fun onFailed(e: Exception) = lost(StateItem.ODOMETER, e)
        }.let {
            owner.getOdometer().registerDistanceDriven(it)
            undo += { owner.getOdometer().unregisterDistanceDriven(it) }
        }
        if (wanted(StateItem.WINDOWS)) s.windows.forEach { area ->
            val sdkArea = WindowArea(WINDOWS.getValue(area))
            val listener = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) {
                    position?.let { trySend(VehicleEvent.WindowChanged(area, it != 0)) }
                }
                override fun onFailed(e: Exception) = lost(StateItem.WINDOWS, e)
            }
            window.registerWindowPosition(sdkArea, listener)
            undo += { window.unregisterWindowPosition(sdkArea, listener) }
        }
        fun lightListener(item: StateItem) = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) {
                state?.let { trySend(VehicleEvent.LightChanged(item, it == LightState.ON)) }
            }
            override fun onFailed(e: Exception) = lost(item, e)
        }
        if (wanted(StateItem.HEADLIGHTS)) lightListener(StateItem.HEADLIGHTS).let {
            light.registerLowBeamLightState(it)
            undo += { light.unregisterLowBeamLightState(it) }
        }
        if (wanted(StateItem.TAIL_LAMP)) lightListener(StateItem.TAIL_LAMP).let {
            light.registerTailLampLightState(it)
            undo += { light.unregisterTailLampLightState(it) }
        }
        if (wanted(StateItem.CABIN_LIGHT)) lightListener(StateItem.CABIN_LIGHT).let {
            light.registerCabinLightState(it)
            undo += { light.unregisterCabinLightState(it) }
        }
        if (wanted(StateItem.SEATS)) s.seats.forEach { area ->
            val sdkArea = SeatArea(SEATS.getValue(area))
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                    occupancy?.let { trySend(VehicleEvent.SeatOccupancyChanged(area, occupied(it))) }
                }
                override fun onFailed(e: Exception) = lost(StateItem.SEATS, e)
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
            override fun onFailed(e: Exception) = lost(item, e)
        }
        if (wanted(StateItem.ENGINE_OIL)) oilListener(StateItem.ENGINE_OIL).let {
            safety.registerEngineOilLevelState(it)
            undo += { safety.unregisterEngineOilLevelState(it) }
        }
        if (wanted(StateItem.BRAKE_FLUID)) oilListener(StateItem.BRAKE_FLUID).let {
            safety.registerBrakeFluidLevelState(it)
            undo += { safety.unregisterBrakeFluidLevelState(it) }
        }
        if (wanted(StateItem.WASHER_FLUID)) object : LowWasherFluidWaringListener {
            override fun onLowWasherFluidWaringUpdated(state: Boolean?) {
                state?.let { trySend(VehicleEvent.FluidChanged(StateItem.WASHER_FLUID, if (it) FluidLevel.LOW else FluidLevel.NORMAL)) }
            }
            override fun onFailed(e: Exception) = lost(StateItem.WASHER_FLUID, e)
        }.let {
            safety.registerLowWasherFluidWaringState(it)
            undo += { safety.unregisterLowWasherFluidWaringState(it) }
        }
        if (wanted(StateItem.TELLTALES)) object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                telltales?.let { trySend(VehicleEvent.TelltalesChanged(it.warnings())) }
            }
            override fun onFailed(e: Exception) = lost(StateItem.TELLTALES, e)
        }.let {
            safety.registerTelltales(it)
            undo += { safety.unregisterTelltales(it) }
        }
        if (wanted(StateItem.AIRBAGS)) s.airbags.forEach { area ->
            val sdkArea = SeatArea(SEATS.getValue(area))
            val listener = object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(deployed: Boolean?) {
                    deployed?.let { trySend(VehicleEvent.AirbagChanged(area, it)) }
                }
                override fun onFailed(e: Exception) = lost(StateItem.AIRBAGS, e)
            }
            safety.registerSeatAirbagDeployed(sdkArea, listener)
            undo += { safety.unregisterSeatAirbagDeployed(sdkArea, listener) }
        }

        Log.i(TAG, "구독 시작: $heard")
        awaitClose {
            undo.forEach { runCatching(it) }
            Log.i(TAG, "구독 해제: $heard")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 기어가 P인지. 인수·반납 화면 단계를 정차 중에만 열 때 쓴다(요구사항 SAF-01). 읽지 못하면 아무것도 내보내지 않는다.
     * SDK는 AAOS 표준 GEAR_SELECTION 속성을 권하지만, 그러려면 Car API 라이브러리와 권한이 더 필요해서 R1은 이 API를 쓴다.
     * 구독하는 동안만 리스너를 걸므로 화면이 뒤로 가 구독을 끊으면 기어도 듣지 않는다.
     */
    fun parked(): Flow<Boolean> = parkedEvents

    @Suppress("DEPRECATION")
    private fun gearEvents(): Flow<Boolean> = callbackFlow {
        val gear = gearVehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                Log.i(TAG, "기어: $gear")
                gear?.let { trySend(it == VehicleGear.GEAR_PARK) }
            }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "기어 구독 실패: ${e.message}")
            }
        }
        gear.registerGearSelection(listener)
        (call<VehicleGear?> { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) } as? Outcome.Ok)?.value
            ?.let { trySend(it == VehicleGear.GEAR_PARK) }
        awaitClose {
            runCatching { gear.unregisterGearSelection(listener) }
            Log.i(TAG, "기어 구독 해제")
        }
    }.flowOn(Dispatchers.IO).catch { Log.w(TAG, "기어를 구독하지 못했습니다: ${it.message}") }.distinctUntilChanged()

    override fun close() {
        background.cancel()
        vehicle.release()
        screenVehicle.release()
        gearVehicle.release()
    }

    companion object {
        private const val TELLTALE_PERMISSION = "pleos.car.permission.PRIVATE_CAR_SAFETY"

        /** 경고등을 읽는 권한을 받았는지. 에뮬레이터는 선언만 하면 받지만 실차는 App Market 승인이 필요하다. */
        fun telltaleGranted(context: Context): Boolean = context.checkSelfPermission(TELLTALE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun support(): Support =
        cachedSupport ?: supportLock.withLock { cachedSupport ?: discover().also { if (it.reliable) cachedSupport = it } }

    // 부팅 직후에는 차량 서비스가 아직 없어 지원 확인이 모두 실패할 수 있다. 그 상태로 리스너를 걸면 아무 값도 오지 않아 '변화 없음'처럼 보인다.
    // 잠깐 기다려 보고 그래도 안 되면 예외를 던진다. 대여 중 기록은 끊긴 구간으로 남기고 다시 구독한다.
    private suspend fun connectedSupport(): Support {
        repeat(CONNECT_TRIES) {
            val s = support()
            if (s.reliable) return s
            delay(CONNECT_EVERY)
        }
        throw IllegalStateException("차량 서비스에 연결하지 못했습니다")
    }

    private suspend fun discover(): Support = coroutineScope {
        val scalars = listOf(
            scalar(StateItem.FUEL) { ok, fail -> vehicle.getFuel().checkFuelPercentCapabilities(ok, fail) },
            scalar(StateItem.EV_SOC) { ok, fail -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) },
            scalar(StateItem.EV_CHARGING) { ok, fail -> vehicle.getEvBattery().checkEvChargeSwitchCapabilities(ok, fail) },
            scalar(StateItem.ODOMETER) { ok, fail -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, fail) },
            scalar(StateItem.TRIP_SINCE_REFILL) { ok, fail ->
                vehicle.getOdometer().checkTripSinceRefillDrivingDistanceCapabilities(ok, fail)
            },
            scalar(StateItem.CHARGE_PORT) { ok, fail -> vehicle.getEvBattery().checkEvChargePortOpenCapabilities(ok, fail) },
            listed<OilLevelState>(StateItem.ENGINE_OIL) { ok, fail -> vehicle.getSafety().checkEngineOilLevelStateCapabilities(ok, fail) },
            listed<OilLevelState>(StateItem.BRAKE_FLUID) { ok, fail -> vehicle.getSafety().checkBrakeFluidLevelStateCapabilities(ok, fail) },
            scalar(StateItem.WASHER_FLUID) { ok, fail -> vehicle.getSafety().checkLowWasherFluidWarningStateCapabilities(ok, fail) },
            scalar(StateItem.TELLTALES) { ok, fail -> vehicle.getSafety().checkTelltalesCapabilities(ok, fail) },
            listed<LightSwitch>(StateItem.HEADLIGHTS) { ok, fail -> vehicle.getLight().checkLowBeamLightSwitchCapabilities(ok, fail) },
            listed<LightSwitch>(StateItem.TAIL_LAMP) { ok, fail -> vehicle.getLight().checkTailLampLightSwitchCapabilities(ok, fail) },
            listed<LightSwitch>(StateItem.CABIN_LIGHT) { ok, fail -> vehicle.getLight().checkCabinLightSwitchCapabilities(ok, fail) },
        )
        val doorCapability = async { capability<Map<DoorArea, *>> { ok, fail -> vehicle.getDoor().checkDoorPositionCapabilities(ok, fail) } }
        val doors = async { doorCapability.await().areas(DOORS) { map -> map.keys.flatMap { it.values.toList() } } }
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
        val airbags = async {
            capability<List<SeatArea>> { ok, fail -> vehicle.getSafety().checkAirbagDeployedCapabilities(ok, fail) }
                .areas(SEATS) { list -> list.flatMap { it.values.toList() } }
        }
        val checked = scalars.map { it.await() } + (StateItem.TRUNK to doorCapability.await().listsTrunk())
        val areaLists = mapOf(
            StateItem.DOORS to doors.await(), StateItem.WINDOWS to windows.await(),
            StateItem.SEATS to seats.await(), StateItem.TIRES to wheels.await(), StateItem.AIRBAGS to airbags.await(),
        )
        val unsupported = checked.mapNotNull { (item, ok) -> item.takeIf { ok == false } }.toSet() +
            areaLists.filterValues { it.areas.isEmpty() }.keys
        Support(
            // 배터리가 없는 차에는 배터리 용량도 없다. 전용 지원 확인 API가 문서에 없어 배터리로 판단한다.
            unsupported = if (StateItem.EV_SOC in unsupported) unsupported + StateItem.EV_CAPACITY else unsupported,
            doors = areaLists.getValue(StateItem.DOORS).areas,
            windows = areaLists.getValue(StateItem.WINDOWS).areas,
            seats = areaLists.getValue(StateItem.SEATS).areas,
            wheels = areaLists.getValue(StateItem.TIRES).areas,
            airbags = AIRBAG_SEATS.filter { it in areaLists.getValue(StateItem.AIRBAGS).areas },
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

    // 지원 값 목록을 함께 주는 형식(라이트 스위치, 소모품 단계). 목록이 비었으면 지원하지 않는 것이다.
    private fun <V> CoroutineScope.listed(item: StateItem, block: Capability<List<V>>) = async {
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

    // 문 위치 지원 목록에 트렁크가 있는지. 확인하지 못했으면 null이고 일단 읽어 본다.
    private fun Outcome<Pair<CapabilityConfig?, Map<DoorArea, *>?>>.listsTrunk(): Boolean? = when (this) {
        is Outcome.Ok -> value.first?.accessType != AccessType.NONE && value.second?.keys?.any { DoorValue.REAR in it.values } != false
        is Outcome.Fail -> if (reason == Unavailable.Unsupported) false else null
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
        val airbags: List<Area>,
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

// 2열 차량 기준. 3열과 후드(DoorValue.HOOD), 선루프(ROOF_TOP)는 아직 반납 체크 항목에 넣지 않았다. 트렁크(REAR)는 StateItem.TRUNK로 따로 읽는다.
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

// 값이 그대로여도 리스너가 되풀이해서 주는 항목이 있다(에뮬레이터의 주행거리는 몇 초마다 온다). 달라진 것만 내보내 그때마다 차를 다시 읽지 않게 한다.
private fun Flow<VehicleEvent>.onlyChanges(dropFirst: Boolean): Flow<VehicleEvent> = flow {
    val last = mutableMapOf<Pair<StateItem?, Area?>, VehicleEvent>()
    collect { event ->
        val before = last.put(event.item to event.area, event)
        if (before != event && (before != null || !dropFirst)) emit(event)
    }
}

// 위치별 항목은 위치마다 따로 비교한다.
private val VehicleEvent.area: Area?
    get() = when (this) {
        is VehicleEvent.DoorChanged -> area
        is VehicleEvent.WindowChanged -> area
        is VehicleEvent.SeatOccupancyChanged -> area
        is VehicleEvent.AirbagChanged -> area
        else -> null
    }

private const val TAG = "ChagokVehicle"

private const val CONNECT_TRIES = 15
private val CONNECT_EVERY = 2.seconds

/** 기록에 남길 값은 소수 첫째 자리까지만. Float을 그대로 옮기면 23722.400390625처럼 뜻 없는 자리가 기록에 남는다. */
private fun Double.oneDecimal(): Double = (this * 10).roundToLong() / 10.0

/**
 * SDK 예외의 오류 코드로 "지원 안 함"과 "읽지 못함"을 나눈다. 차량 서비스에 속성이나 영역이 아예 없으면 SDK가 오류 코드 없이
 * "property ID is not in carPropertyConfig list, and so it is not supported", "area ID: 0x1 not supported for property ID"를 주므로 메시지도 본다
 * (2026.11.02 에뮬레이터 확인). 이것을 읽기 실패로 두면 전기차 리포트가 '확인 불가'로 가득 찬다.
 */
internal fun reasonOf(e: Throwable): Unavailable = when (val code = errorCodeOf(e)) {
    "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE" -> Unavailable.Unsupported
    "ERROR_SECURITY_PERMISSION" -> Unavailable.Failed("PERMISSION")
    "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> Unavailable.Failed("SDK_ERROR")
    "ERROR_DRIVING_RESTRICTED" -> Unavailable.Failed("DRIVING_RESTRICTED")
    "ERROR_EMPTY_RESULT" -> Unavailable.Failed("EMPTY")
    else -> if (e.message?.contains("not supported") == true) Unavailable.Unsupported else Unavailable.Failed(code ?: e.javaClass.simpleName)
}

// SDK 2.3.2의 예외 클래스와 메서드 이름은 난독화돼 있어(callback.exception.a.a()) 반환 형식(ErrorCode)으로 찾는다.
// 권한이 모자라면 SDK가 "need Permission"만 남기고 오류 코드를 주지 않을 때가 있어 메시지와 예외 종류도 본다.
private fun errorCodeOf(e: Throwable): String? {
    val getter = e.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType.simpleName == "ErrorCode" }
    return runCatching { (getter?.invoke(e) as? Enum<*>)?.name }.getOrNull()
        ?: ERROR_CODES.firstOrNull { e.message?.contains(it) == true }
        ?: "ERROR_SECURITY_PERMISSION".takeIf { e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true }
}
