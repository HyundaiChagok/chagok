package app.chagok.core.fake

import app.chagok.core.model.Area
import app.chagok.core.model.GeoPoint
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.TireState
import app.chagok.core.model.VehicleState
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * 시연·테스트용 대본. "10분 대기 → 4.2km 주행 → 운전석 문 열림" 같은 흐름을 차 없이 재생한다.
 *
 * ```
 * val valet = scenario {
 *     update("정비소 이동") { driven(km = 4.2, fuelUsedPercent = 1.0) }
 *     wait(30.minutes)
 *     update("오일 보충") { copy(engineOil = FluidLevel.NORMAL) }
 * }
 * valet.play(vehicle, location)
 * ```
 */
class Scenario internal constructor(val steps: List<Step>) {
    sealed interface Step {
        data class Wait(val duration: Duration) : Step
        class Update(val label: String, val transform: VehicleState.() -> VehicleState) : Step
        data class MoveTo(val point: GeoPoint) : Step
    }
}

class ScenarioBuilder internal constructor() {
    private val steps = mutableListOf<Scenario.Step>()

    fun wait(duration: Duration) {
        steps += Scenario.Step.Wait(duration)
    }

    fun update(label: String = "", transform: VehicleState.() -> VehicleState) {
        steps += Scenario.Step.Update(label, transform)
    }

    fun moveTo(point: GeoPoint) {
        steps += Scenario.Step.MoveTo(point)
    }

    internal fun build() = Scenario(steps.toList())
}

fun scenario(block: ScenarioBuilder.() -> Unit): Scenario = ScenarioBuilder().apply(block).build()

/** [onStep]으로 지금 어느 단계인지 화면에 띄울 수 있다. */
suspend fun Scenario.play(
    vehicle: FakeVehicle,
    location: FakeLocation? = null,
    onStep: (Scenario.Step) -> Unit = {},
) {
    for (step in steps) {
        onStep(step)
        when (step) {
            is Scenario.Step.Wait -> delay(step.duration)
            is Scenario.Step.Update -> vehicle.update(step.transform)
            is Scenario.Step.MoveTo -> requireNotNull(location) { "대본에 위치 이동이 있으면 FakeLocation이 필요합니다" }
                .moveTo(step.point)
        }
    }
}

// 대본에서 자주 쓰는 상태 변경

fun VehicleState.driven(km: Double, fuelUsedPercent: Double = 0.0): VehicleState = copy(
    odometerKm = odometerKm?.plus(km),
    tripSinceRefillKm = tripSinceRefillKm?.plus(km),
    fuelPercent = fuelPercent?.minus(fuelUsedPercent)?.coerceAtLeast(0.0),
)

fun VehicleState.refueled(toPercent: Double = 100.0): VehicleState =
    copy(fuelPercent = toPercent, tripSinceRefillKm = 0.0)

fun VehicleState.withDoor(area: Area, open: Boolean): VehicleState = copy(doorsOpen = doorsOpen + (area to open))

fun VehicleState.withWindow(area: Area, open: Boolean): VehicleState = copy(windowsOpen = windowsOpen + (area to open))

fun VehicleState.withSeat(area: Area, occupied: Boolean): VehicleState =
    copy(seatsOccupied = seatsOccupied + (area to occupied))

/** item은 HEADLIGHTS, TAIL_LAMP, CABIN_LIGHT 중 하나 */
fun VehicleState.withLight(item: StateItem, on: Boolean): VehicleState = when (item) {
    StateItem.HEADLIGHTS -> copy(headlightsOn = on)
    StateItem.TAIL_LAMP -> copy(tailLampOn = on)
    StateItem.CABIN_LIGHT -> copy(cabinLightOn = on)
    else -> throw IllegalArgumentException("라이트가 아닌 항목: $item")
}

fun VehicleState.withTelltale(telltale: Telltale, on: Boolean = true): VehicleState =
    copy(telltales = telltales.orEmpty().let { if (on) it + telltale else it - telltale })

fun VehicleState.withLowTire(area: Area, low: Boolean = true): VehicleState =
    copy(tires = tires + (area to (tires[area] ?: TireState()).copy(lowPressure = low)))
