package app.chagok.core.rental

import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withWindow
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.source.VehicleSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReturnCheckTest {

    @Test
    fun `고칠 수 있는 것부터 좌석, 잔량, 경고등 순으로 늘어놓는다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val pickup = VehicleState(fuelPercent = 82.0, telltales = emptySet())
        val now = VehicleState(
            fuelPercent = 61.0,
            telltales = setOf(engine),
            windowsOpen = mapOf(Area.FRONT_RIGHT to true, Area.FRONT_LEFT to false),
            doorsOpen = mapOf(Area.FRONT_LEFT to false),
            headlightsOn = false,
            cabinLightOn = true,
            seatsOccupied = mapOf(Area.FRONT_LEFT to true, Area.REAR_LEFT to true),
        )
        assertEquals(
            listOf(
                CheckItem.WindowOpen(Area.FRONT_RIGHT),
                CheckItem.LightOn(StateItem.CABIN_LIGHT),
                CheckItem.SeatDetected(Area.REAR_LEFT),
                CheckItem.EnergyDrop(StateItem.FUEL, 82, 61),
                CheckItem.NewTelltales(setOf(engine)),
            ),
            ReturnCheck.items(pickup, now),
        )
    }

    @Test
    fun `잔량이 허용치 안이거나 인수 때부터 켜진 경고등은 알리지 않는다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val items = ReturnCheck.items(
            VehicleState(fuelPercent = 82.0, telltales = setOf(engine)),
            VehicleState(fuelPercent = 80.5, telltales = setOf(engine)),
        )
        assertEquals(emptyList(), items)
    }

    @Test
    fun `고객이 고치면 목록에서 바로 빠진다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(windowsOpen = mapOf(Area.FRONT_RIGHT to true), cabinLightOn = true))
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = null).collect { seen += it } }
        runCurrent()

        vehicle.update { withWindow(Area.FRONT_RIGHT, false) }
        runCurrent()
        vehicle.update { withLight(StateItem.CABIN_LIGHT, false) }
        runCurrent()

        assertEquals(
            listOf(
                listOf(CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.LightOn(StateItem.CABIN_LIGHT)),
                listOf(CheckItem.LightOn(StateItem.CABIN_LIGHT)),
                emptyList(),
            ),
            seen,
        )
    }

    @Test
    fun `이벤트가 없는 값은 주기적으로 다시 읽는다`() = runTest {
        var state = VehicleState(headlightsOn = true)
        val silent = object : VehicleSource {
            override suspend fun state() = state
            override fun changes(): Flow<VehicleEvent> = emptyFlow()
        }
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(silent, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        state = VehicleState(headlightsOn = false)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf(listOf(CheckItem.LightOn(StateItem.HEADLIGHTS)), emptyList()), seen)
    }

    private fun unread(vararg items: StateItem): Map<StateItem, Unavailable> = items.associateWith { Unavailable.Failed("TIMEOUT") }

    private class Polled(var state: VehicleState) : VehicleSource {
        var broken = false

        override suspend fun state(): VehicleState {
            check(!broken) { "차 서비스 없음" }
            return state
        }

        override fun changes(): Flow<VehicleEvent> = emptyFlow()
    }

    @Test
    fun `못 읽은 문·창문·라이트는 문제없음으로 보지 않는다`() {
        val now = VehicleState(
            doorsOpen = mapOf(Area.FRONT_LEFT to false),
            headlightsOn = false,
            unavailable = unread(StateItem.WINDOWS, StateItem.TAIL_LAMP) + (StateItem.CABIN_LIGHT to Unavailable.Unsupported),
        )
        val items = ReturnCheck.items(VehicleState(), now)

        assertEquals(listOf(CheckItem.Unreadable(StateItem.WINDOWS), CheckItem.Unreadable(StateItem.TAIL_LAMP)), items)
        assertEquals("반납 전에 2가지를 확인해 주세요. 창문, 미등을 읽지 못했습니다. 직접 확인해 주세요.", Voice.returnCheck(items))
    }

    @Test
    fun `트렁크와 충전구가 열려 있으면 문 다음에 고칠 항목으로 알린다`() {
        val items = ReturnCheck.items(
            VehicleState(),
            VehicleState(
                windowsOpen = mapOf(Area.FRONT_RIGHT to true), doorsOpen = mapOf(Area.REAR_LEFT to true),
                trunkOpen = true, chargePortOpen = true, cabinLightOn = true,
            ),
        )
        assertEquals(
            listOf(
                CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.DoorOpen(Area.REAR_LEFT),
                CheckItem.ClosureOpen(StateItem.TRUNK), CheckItem.ClosureOpen(StateItem.CHARGE_PORT),
                CheckItem.LightOn(StateItem.CABIN_LIGHT),
            ),
            items,
        )
        assertTrue(CheckItem.ClosureOpen(StateItem.TRUNK).fixable)
        assertEquals("열림", CheckItem.ClosureOpen(StateItem.CHARGE_PORT).detail)
    }

    @Test
    fun `트렁크를 닫으면 목록에서 바로 빠진다`() = runTest {
        val vehicle = FakeVehicle(VehicleState(trunkOpen = true, chargePortOpen = true))
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = null).collect { seen += it } }
        runCurrent()

        vehicle.update { copy(trunkOpen = false) }
        runCurrent()

        assertEquals(
            listOf<List<CheckItem>>(
                listOf(CheckItem.ClosureOpen(StateItem.TRUNK), CheckItem.ClosureOpen(StateItem.CHARGE_PORT)),
                listOf(CheckItem.ClosureOpen(StateItem.CHARGE_PORT)),
            ),
            seen,
        )
    }

    @Test
    fun `읽지 못하게 된 트렁크는 닫혔다고 하지 않는다`() {
        val done = CheckItem.ClosureOpen(StateItem.TRUNK)
        assertTrue(done.lostReading(listOf(CheckItem.Unreadable(StateItem.TRUNK))))
        assertEquals(null, Voice.resolved(done, listOf(CheckItem.Unreadable(StateItem.TRUNK))))
    }

    @Test
    fun `지원하지 않는 항목은 확인할 것이 아니다`() {
        val now = VehicleState(unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported))
        assertEquals(emptyList(), ReturnCheck.items(VehicleState(), now))
    }

    @Test
    fun `읽지 못한 항목은 고칠 수 있는 것 뒤, 좌석 앞에 둔다`() {
        val now = VehicleState(
            doorsOpen = mapOf(Area.FRONT_LEFT to true),
            seatsOccupied = mapOf(Area.REAR_LEFT to true),
            unavailable = unread(StateItem.WINDOWS),
        )
        assertEquals(
            listOf(CheckItem.DoorOpen(Area.FRONT_LEFT), CheckItem.Unreadable(StateItem.WINDOWS), CheckItem.SeatDetected(Area.REAR_LEFT)),
            ReturnCheck.items(VehicleState(), now),
        )
    }

    @Test
    fun `일시 실패 때는 직전 값을 유지해 열린 창문이 닫혔다고 하지 않는다`() = runTest {
        val open = VehicleState(windowsOpen = mapOf(Area.FRONT_RIGHT to true, Area.FRONT_LEFT to false), doorsOpen = mapOf(Area.FRONT_LEFT to false))
        val vehicle = Polled(open)
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        // 두 번까지는 직전 값을 유지한다.
        vehicle.state = open.copy(windowsOpen = emptyMap(), unavailable = unread(StateItem.WINDOWS))
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(listOf(listOf<CheckItem>(CheckItem.WindowOpen(Area.FRONT_RIGHT))), seen)

        // 그 뒤로는 고객이 고쳤을 수도 있으니 읽지 못함으로 드러낸다. 닫혔다고 말하지 않는다.
        advanceTimeBy(2.seconds)
        runCurrent()
        val lost = listOf<CheckItem>(CheckItem.Unreadable(StateItem.WINDOWS))
        assertEquals(lost, seen.last())
        assertEquals(null, Voice.resolved(CheckItem.WindowOpen(Area.FRONT_RIGHT), lost))

        // 다시 읽히면 사실대로 돌아온다.
        vehicle.state = open.copy(windowsOpen = mapOf(Area.FRONT_RIGHT to false, Area.FRONT_LEFT to false))
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(emptyList(), seen.last())
        assertEquals(
            "창문을 다시 읽었습니다. 고칠 것은 모두 끝났습니다. 반납 기록을 남길까요?",
            Voice.resolved(CheckItem.Unreadable(StateItem.WINDOWS), emptyList()),
        )
    }

    @Test
    fun `읽기가 한 번 성공하면 실패 횟수는 처음부터 다시 센다`() = runTest {
        val open = VehicleState(windowsOpen = mapOf(Area.FRONT_RIGHT to true))
        val failing = VehicleState(unavailable = unread(StateItem.WINDOWS))
        val vehicle = Polled(open)
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        repeat(3) {
            vehicle.state = failing
            advanceTimeBy(4.seconds)
            runCurrent()
            vehicle.state = open
            advanceTimeBy(2.seconds)
            runCurrent()
        }
        assertEquals(listOf(listOf<CheckItem>(CheckItem.WindowOpen(Area.FRONT_RIGHT))), seen)
    }

    @Test
    fun `좌석과 잔량, 경고등은 못 읽는 동안 직전 값을 유지한다`() = runTest {
        val engine = Telltale("ENGINE", "엔진 점검")
        val vehicle = Polled(
            VehicleState(seatsOccupied = mapOf(Area.REAR_LEFT to true), fuelPercent = 60.0, telltales = setOf(engine)),
        )
        val seen = mutableListOf<List<CheckItem>>()
        val pickup = VehicleState(fuelPercent = 82.0, telltales = emptySet())
        backgroundScope.launch { ReturnCheck.watch(vehicle, pickup, pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        vehicle.state = VehicleState(unavailable = unread(StateItem.SEATS, StateItem.FUEL, StateItem.TELLTALES))
        advanceTimeBy(20.seconds)
        runCurrent()

        assertEquals(1, seen.size, seen.toString())
        assertEquals(CheckItem.SeatDetected(Area.REAR_LEFT), seen.single().first())
    }

    @Test
    fun `차를 읽다 예외가 나도 멈추지 않고 직전 값을 유지한다`() = runTest {
        val closed = VehicleState(
            windowsOpen = mapOf(Area.FRONT_RIGHT to false), doorsOpen = mapOf(Area.FRONT_LEFT to false),
            headlightsOn = false, tailLampOn = false, trunkOpen = false,
            unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported, StateItem.CHARGE_PORT to Unavailable.Unsupported),
        )
        val vehicle = Polled(closed.copy(windowsOpen = mapOf(Area.FRONT_RIGHT to true)))
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        vehicle.broken = true
        advanceTimeBy(2.seconds)
        runCurrent()
        vehicle.broken = false
        vehicle.state = closed
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf(listOf(CheckItem.WindowOpen(Area.FRONT_RIGHT)), emptyList()), seen)
    }

    @Test
    fun `읽기가 계속 예외를 내면 지원하는 항목만 읽지 못함이 된다`() = runTest {
        val vehicle = Polled(
            VehicleState(
                windowsOpen = mapOf(Area.FRONT_RIGHT to false), doorsOpen = mapOf(Area.FRONT_LEFT to false),
                headlightsOn = false, tailLampOn = false, trunkOpen = false,
                unavailable = mapOf(StateItem.CABIN_LIGHT to Unavailable.Unsupported, StateItem.CHARGE_PORT to Unavailable.Unsupported),
            ),
        )
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = 2.seconds).collect { seen += it } }
        runCurrent()

        vehicle.broken = true
        advanceTimeBy(6.seconds)
        runCurrent()

        assertEquals(
            listOf(StateItem.WINDOWS, StateItem.DOORS, StateItem.TRUNK, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP).map { CheckItem.Unreadable(it) },
            seen.last(),
        )
    }

    @Test
    fun `처음부터 못 읽으면 바로 읽지 못함이다`() = runTest {
        val vehicle = Polled(VehicleState(unavailable = unread(StateItem.DOORS)))
        val seen = mutableListOf<List<CheckItem>>()
        backgroundScope.launch { ReturnCheck.watch(vehicle, VehicleState(), pollEvery = null).collect { seen += it } }
        runCurrent()

        assertEquals(listOf(listOf<CheckItem>(CheckItem.Unreadable(StateItem.DOORS))), seen)
    }

    @Test
    fun `인수 때 경고등을 못 읽었으면 새로 켜졌다고 하지 않고 나눠서 알린다`() {
        val engine = Telltale("ENGINE", "엔진 점검")
        val pickup = VehicleState(unavailable = unread(StateItem.TELLTALES))
        val items = ReturnCheck.items(pickup, VehicleState(telltales = setOf(engine)))

        assertEquals(listOf<CheckItem>(CheckItem.TelltalesUnverified(setOf(engine))), items)
        assertEquals("켜짐 1 · 인수 때 확인 못 함", items.single().detail)
        assertEquals(
            "고칠 것이 없습니다. 켜진 경고등 1개를 기록에 남깁니다. 인수 때는 확인하지 못했습니다. 반납 기록을 남길까요?",
            Voice.returnCheck(items),
        )
        // 지금 켜진 것이 없으면 알릴 것도 없다.
        assertEquals(emptyList(), ReturnCheck.items(pickup, VehicleState(telltales = emptySet())))
    }
}
