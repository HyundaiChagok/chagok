package app.chagok.rental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.chagok.AppSettings
import app.chagok.BuildConfig
import app.chagok.core.fake.withWindow
import app.chagok.core.gleo.GleoFunction
import app.chagok.core.gleo.GleoMode
import app.chagok.core.gleo.GleoReply
import app.chagok.core.gleo.GleoStatus
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.rental.RentalPolicy
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ReturnPoint
import app.chagok.core.rental.ReturnTrip
import app.chagok.core.rental.Voice
import app.chagok.core.source.ReturnNavigation
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.ReplyListener
import app.chagok.core.voice.VoiceEngine
import app.chagok.demo.DemoVehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 내비 없이 도는 가짜. 무엇을 요청받았는지 적어 두고, 이벤트는 시험이 직접 보낸다. */
private class FakeNavigation : ReturnNavigation {
    override val connected = MutableStateFlow(false)
    override val arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val routeCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val routeFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    var estimate: Int? = 58
    var gate: CompletableDeferred<Unit>? = null
    val estimated = mutableListOf<ReturnPoint>()
    val routes = mutableListOf<ReturnPoint>()
    var chargers = 0
    var connects = 0
    var disconnects = 0
    var reachable = true
    override var guiding = false
    var cancels = 0

    override fun cancelRoute() {
        cancels++
        guiding = false
    }

    override fun connect() {
        connects++
        connected.value = reachable
    }

    override fun disconnect() {
        disconnects++
        connected.value = false
        guiding = false
    }

    override suspend fun estimateArrivalPercent(point: ReturnPoint): Int? {
        estimated += point
        gate?.await()
        return estimate
    }

    override fun startRoute(point: ReturnPoint) {
        routes += point
        guiding = true
    }

    override fun showNearbyChargers() {
        chargers++
    }
}

/** 들은 말을 시험이 정한다. 답을 정하지 않으면 시간이 다 될 때까지 기다린다. */
private class FakeReplies : ReplyListener {
    var heard: String? = null
    var waitsFull = false
    var calls = 0
    var within: Duration? = null
    var cancelled = 0
    var warmed = 0
    var released = 0

    override fun warmUp() {
        warmed++
    }

    override suspend fun listen(within: Duration): String? {
        calls++
        this.within = within
        try {
            if (waitsFull) delay(within)
            return heard
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }

    override fun release() {
        released++
    }
}

private class FakeNotice : ReturnTripKeeper.ArrivalNotice {
    var shown = 0
    var cancelled = 0

    override fun show() {
        shown++
    }

    override fun cancel() {
        cancelled++
    }
}

/** 전기차 시연 값. 인수 때 82%. */
private val EV_START = DemoVehicle.START.copy(
    fuelPercent = null, tripSinceRefillKm = null, evSocPercent = 82.0, evCapacityKwh = 77.4,
    unavailable = DemoVehicle.START.unavailable.filterKeys { it != StateItem.EV_SOC && it != StateItem.EV_CAPACITY } +
        mapOf(StateItem.FUEL to Unavailable.Unsupported, StateItem.TRIP_SINCE_REFILL to Unavailable.Unsupported),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReturnTripAndGleoTest {
    private val dispatcher = StandardTestDispatcher()
    private val folder: File = Files.createTempDirectory("chagok").toFile()
    private val store = RecordStore(folder)
    private val viewModels = mutableListOf<ViewModelStore>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val clock = Instant.parse("2026-11-03T00:12:00Z")

    private val vehicle = TestVehicle(EV_START)
    private val voice = FakeVoice()
    private val navi = FakeNavigation()
    private val notice = FakeNotice()
    private val parked = MutableStateFlow(true)
    private val gleoReplies = mutableListOf<GleoReply>()
    private val settings = AppSettings(reportBaseUrl = "https://example.com/chagok")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        folder.deleteRecursively()
    }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            viewModels.forEach { it.clear() }
            scopes.forEach { it.cancel() }
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }

    private fun keeper(canSimulate: Boolean = false, source: ReturnNavigation? = navi, doors: VehicleSource? = vehicle) = ReturnTripKeeper(
        source, store, { voice }, parked, notice, newScope(), settings.returnPoint, canSimulate, doors,
    )

    private fun controller(
        trip: ReturnTripKeeper? = keeper(),
        records: RecordStore = store,
        spoken: Flow<String> = emptyFlow(),
        replies: ReplyListener? = null,
    ): RentalController {
        val timeline = TimelineKeeper(vehicle, records, newScope()) { clock }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RentalController(
                vehicle, records, voice, settings, parked, timeline, { clock }, {}, {}, dispatcher, trip, spoken, { gleoReplies += it }, replies,
            ) as T
        }
        val owner = ViewModelStore().also { viewModels += it }
        return ViewModelProvider(owner, factory)[RentalController::class.java]
    }

    private fun TestScope.pickUp(c: RentalController) {
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
    }

    // 반납하러 가기

    @Test
    fun `대여가 열리면 내비에 연결하고 고객이 리포트를 닫으면 끊는다`() = scenario {
        val c = controller()
        runCurrent()
        assertEquals(0, navi.connects, "대기 중에는 연결하지 않는다")
        assertFalse(c.ui.value.naviReady)

        pickUp(c)
        assertEquals(1, navi.connects)
        assertTrue(c.ui.value.naviReady)

        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertEquals(0, navi.disconnects, "길안내를 취소하면 내비가 앞으로 나와 리포트의 QR을 가린다")

        c.close()
        runCurrent()
        assertEquals(1, navi.disconnects)
        assertFalse(c.ui.value.naviReady)
    }

    @Test
    fun `내비에 연결되지 않으면 버튼을 숨길 수 있게 알리고 눌러도 아무 일도 없다`() = scenario {
        navi.reachable = false
        val c = controller()
        pickUp(c)
        assertFalse(c.ui.value.naviReady)

        val none = controller(trip = null)
        runCurrent()
        assertFalse(none.ui.value.naviReady)
    }

    @Test
    fun `연결에 실패했으면 화면이 다시 뜰 때 다시 연결한다`() = scenario {
        navi.reachable = false
        val c = controller()
        pickUp(c)
        assertFalse(c.ui.value.naviReady)
        val tries = navi.connects

        navi.reachable = true
        c.setVisible(true)
        runCurrent()
        assertEquals(tries + 1, navi.connects)
        assertTrue(c.ui.value.naviReady)
    }

    @Test
    fun `도착 예상 배터리를 받으면 인수 때와 비교해 보여 주고 낮으면 참고 정산을 준다`() = scenario {
        val c = controller()
        pickUp(c)

        navi.gate = CompletableDeferred()
        c.planReturnTrip()
        runCurrent()
        assertEquals(ReturnTripStep.Estimating, c.ui.value.returnTrip, "기다리는 동안 진행 중임을 보여 준다")
        assertEquals(listOf(settings.returnPoint), navi.estimated)

        navi.gate!!.complete(Unit)
        runCurrent()
        val plan = assertIs<ReturnTripStep.Planned>(c.ui.value.returnTrip).plan
        assertEquals("도착하면 약 58%, 인수 때 82%", plan.summary)
        assertTrue(plan.lower)
        assertEquals("참고 정산(시연 단가): 약 18.6kWh · 6,500원 · 청구 확정은 업체", plan.settlement)
        assertEquals(emptyList(), navi.routes, "고객이 결정하기 전에는 경로를 시작하지 않는다")
        assertEquals(emptyList(), voice.said.drop(2), "말하지 않는다")

        c.showNearbyChargers()
        runCurrent()
        assertEquals(1, navi.chargers)

        c.startReturnRoute()
        runCurrent()
        assertEquals(listOf(settings.returnPoint), navi.routes)
        val guiding = assertIs<ReturnTripStep.Guiding>(c.ui.value.returnTrip)
        assertEquals(plan, guiding.plan)
        assertEquals("반납 지점(시연)", guiding.pointName)
    }

    @Test
    fun `반납 지점은 가상임이 드러나고 서울 안의 좌표다`() {
        val point = settings.returnPoint
        assertTrue(point.name.contains("시연"), point.name)
        assertTrue(point.latitude in 37.4..37.7 && point.longitude in 126.7..127.2, "$point")
    }

    @Test
    fun `경로 안내 중에도 다시 부를 수 있다`() = scenario {
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        assertIs<ReturnTripStep.Guiding>(c.ui.value.returnTrip)

        c.planReturnTrip()
        runCurrent()
        assertIs<ReturnTripStep.Planned>(c.ui.value.returnTrip)
        assertEquals(2, navi.estimated.size)
    }

    @Test
    fun `내비가 값을 주지 않으면 경로 안내만 한다`() = scenario {
        navi.estimate = null
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        assertEquals(listOf(settings.returnPoint), navi.routes)
        assertNull(assertIs<ReturnTripStep.Guiding>(c.ui.value.returnTrip).plan)
    }

    @Test
    fun `인수 때보다 낮지 않으면 충전을 묻지 않는다`() = scenario {
        navi.estimate = 90
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        val plan = assertIs<ReturnTripStep.Planned>(c.ui.value.returnTrip).plan
        assertFalse(plan.lower)
        assertNull(plan.settlement)

        c.closeReturnPlan()
        runCurrent()
        assertNull(c.ui.value.returnTrip)
        assertEquals(emptyList(), navi.routes)
    }

    @Test
    fun `기다리는 동안 반납 체크를 시작하면 늦게 온 값을 버린다`() = scenario {
        navi.gate = CompletableDeferred()
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertNull(c.ui.value.returnTrip)

        navi.gate!!.complete(Unit)
        runCurrent()
        assertNull(c.ui.value.returnTrip)
        assertEquals(emptyList(), navi.routes)
    }

    @Test
    fun `정차 중이 아니거나 대여 중이 아니면 반납하러 가기를 받지 않는다`() = scenario {
        val idle = controller()
        runCurrent()
        idle.planReturnTrip()
        runCurrent()
        assertEquals(emptyList(), navi.estimated)

        pickUp(idle)
        parked.value = false
        runCurrent()
        idle.planReturnTrip()
        runCurrent()
        assertEquals(emptyList(), navi.estimated)
    }

    @Test
    fun `출발하면 비교를 닫고 경로 안내 표시는 남긴다`() = scenario {
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        assertIs<ReturnTripStep.Planned>(c.ui.value.returnTrip)

        parked.value = false
        runCurrent()
        assertNull(c.ui.value.returnTrip, "주행 중에는 묻지 않는다")
        c.startReturnRoute()
        runCurrent()
        assertEquals(emptyList(), navi.routes)

        parked.value = true
        runCurrent()
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        parked.value = false
        runCurrent()
        assertIs<ReturnTripStep.Guiding>(c.ui.value.returnTrip)
    }

    @Test
    fun `차를 읽다 실패해도 실패를 답한다`() = scenario {
        pickUp(controller())
        val broken = object : VehicleSource {
            override suspend fun state(): VehicleState = error("차량 서비스 없음")
            override fun changes(): Flow<VehicleEvent> = emptyFlow()
        }
        desk(broken).whatToCheck("{}")
        runCurrent()
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
    }

    @Test
    fun `경로를 찾지 못하거나 고객이 취소하면 앱은 그대로이고 다시 할 수 있다`() = scenario {
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()

        navi.routeFailed.emit(Unit)
        runCurrent()
        assertNull(c.ui.value.returnTrip)
        assertEquals(ReturnTrip.ROUTE_FAILED, c.ui.value.caption)
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)

        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        navi.routeCancelled.emit(Unit)
        runCurrent()
        assertNull(c.ui.value.returnTrip)
    }

    // 도착

    @Test
    fun `대여 중에 도착하면 말하고 화면이 반납 체크를 제안한다`() = scenario {
        val c = controller()
        pickUp(c)
        c.setVisible(true)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        val said = voice.said.size

        navi.arrivals.emit(Unit)
        runCurrent()

        assertTrue(c.ui.value.arrived)
        assertNull(c.ui.value.returnTrip)
        assertEquals(Voice.arrived(), c.ui.value.caption)
        assertEquals(listOf(Voice.arrived() to false), voice.said.drop(said))
        assertEquals(0, notice.shown, "화면이 떠 있으면 알림을 띄우지 않는다")

        c.startReturnCheck()
        runCurrent()
        assertFalse(c.ui.value.arrived)
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
    }

    @Test
    fun `앱이 뒤에 있으면 알림을 띄우고 앱이 보이면 지운다`() = scenario {
        val c = controller()
        pickUp(c)
        c.setVisible(false)
        runCurrent()

        navi.arrivals.emit(Unit)
        runCurrent()
        assertEquals(1, notice.shown)
        assertTrue(c.ui.value.arrived)

        val cancelled = notice.cancelled
        c.setVisible(true)
        runCurrent()
        assertTrue(notice.cancelled > cancelled)
        assertTrue(c.ui.value.arrived, "들어오면 반납 체크 제안을 그대로 보여 준다")
    }

    @Test
    fun `화면이 없어져도 도착을 받고 새 화면이 이어 받는다`() = scenario {
        val trip = keeper()
        val first = controller(trip)
        pickUp(first)
        first.setVisible(false)
        viewModels.forEach { it.clear() }
        runCurrent()

        navi.arrivals.emit(Unit)
        runCurrent()
        assertEquals(1, notice.shown)
        assertTrue(voice.said.last().first == Voice.arrived())

        val second = controller(trip)
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertTrue(second.ui.value.arrived)
    }

    @Test
    fun `P단이 될 때까지 말하지 않고 안내는 먼저 보여 준다`() = scenario {
        val c = controller()
        pickUp(c)
        val said = voice.said.size
        parked.value = false
        runCurrent()

        navi.arrivals.emit(Unit)
        runCurrent()
        assertTrue(c.ui.value.arrived)
        assertEquals(said, voice.said.size, "주행 중에는 말하지 않는다")

        parked.value = true
        runCurrent()
        assertEquals(Voice.arrived(), voice.said.last().first)
    }

    @Test
    fun `P단이 되기 전에 반납 체크를 시작했으면 도착 안내를 하지 않는다`() = scenario {
        val trip = keeper()
        val c = controller(trip)
        pickUp(c)
        parked.value = false
        runCurrent()
        navi.arrivals.emit(Unit)
        runCurrent()

        trip.clearArrival()
        parked.value = true
        runCurrent()
        assertFalse(voice.said.any { it.first == Voice.arrived() })
    }

    @Test
    fun `대여 중이 아니면 도착을 무시한다`() = scenario {
        val c = controller()
        pickUp(c)
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)

        navi.arrivals.emit(Unit)
        runCurrent()
        assertFalse(c.ui.value.arrived, "반납 체크 중")
        assertFalse(voice.said.any { it.first == Voice.arrived() })
        assertEquals(0, notice.shown)

        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        navi.arrivals.emit(Unit)
        runCurrent()
        assertFalse(c.ui.value.arrived, "반납 뒤")
        assertFalse(voice.said.any { it.first == Voice.arrived() })
    }

    @Test
    fun `도착 정보는 저장하지 않아 앱을 다시 켜면 사라진다`() = scenario {
        val c = controller()
        pickUp(c)
        val before = folder.walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }.filterKeys { it != "alive.txt" }

        navi.arrivals.emit(Unit)
        runCurrent()
        assertTrue(c.ui.value.arrived)
        val after = folder.walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }.filterKeys { it != "alive.txt" }
        assertEquals(before, after, "도착이 파일을 바꾸지 않는다")

        viewModels.forEach { it.clear() }
        scopes.forEach { it.cancel() }
        val again = controller(keeper())
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, again.ui.value.state)
        assertFalse(again.ui.value.arrived)
    }

    @Test
    fun `도착 재현은 에뮬레이터에서 진짜 이벤트와 같은 길로 가고 실차에서는 받지 않는다`() = scenario {
        val real = controller(keeper(canSimulate = false))
        pickUp(real)
        real.simulateArrival()
        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(real.ui.value.arrived, "실차에서는 아무 일도 없다")
        viewModels.forEach { it.clear() }

        val emulated = controller(keeper(canSimulate = true))
        runCurrent()
        emulated.simulateArrival()
        advanceTimeBy(4_900)
        runCurrent()
        assertFalse(emulated.ui.value.arrived, "내비 화면으로 갈 시간을 준다")
        advanceTimeBy(200)
        runCurrent()
        assertTrue(emulated.ui.value.arrived)
        assertEquals(Voice.arrived(), voice.said.last().first)
    }

    @Test
    fun `대여 중이 아니면 도착 재현도 무시한다`() = scenario {
        val c = controller(keeper(canSimulate = true))
        runCurrent()
        c.simulateArrival()
        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(c.ui.value.arrived)
        assertEquals(emptyList(), voice.said)
    }

    // Gleo 입구

    @Test
    fun `Gleo가 반납 체크를 시작하면 앱이 요약을 말하고 Gleo는 말하지 않는다`() = scenario {
        val c = controller()
        pickUp(c)

        c.onGleo(GleoFunction.START_RETURN_CHECK, "{}")
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        val reply = gleoReplies.single()
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals(GleoMode.SILENT, reply.mode)
        assertTrue(voice.said.last().first.startsWith("고칠 것이 없습니다."), voice.said.last().first)
    }

    @Test
    fun `앱을 막 켜서 복원 중이어도 Gleo 호출을 버리지 않는다`() = scenario {
        val first = controller()
        pickUp(first)
        viewModels.forEach { it.clear() }

        val second = controller()
        assertTrue(second.ui.value.restoring)
        second.onGleo(GleoFunction.START_RETURN_CHECK, null)
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, second.ui.value.state)
        assertEquals(GleoStatus.SUCCESS, gleoReplies.single().status)
    }

    @Test
    fun `P단이 아니면 Gleo 호출로 시작하지 않고 이유를 답한다`() = scenario {
        val c = controller()
        pickUp(c)
        parked.value = false
        runCurrent()

        c.onGleo(GleoFunction.START_RETURN_CHECK, "{}")
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
        assertTrue(gleoReplies.single().message.contains("park"))
    }

    @Test
    fun `앱이 뒤에 있었으면 기어를 새로 읽는다`() = scenario {
        val c = controller()
        pickUp(c)
        c.setVisible(false)
        runCurrent()
        parked.value = false
        runCurrent()
        assertTrue(c.ui.value.parked, "뒤에 있는 동안에는 기어를 듣지 않는다")

        c.onGleo(GleoFunction.START_RETURN_CHECK, "{}")
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertFalse(c.ui.value.parked)
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
    }

    @Test
    fun `대여가 없으면 어떤 호출도 대여를 시작하지 않고 이유를 답한다`() = scenario {
        val c = controller()
        runCurrent()
        c.onGleo(GleoFunction.START_RETURN_CHECK, "{}")
        c.onGleo(GleoFunction.SHOW_RECORD, "{}")
        runCurrent()
        assertEquals(RentalState.IDLE, c.ui.value.state)
        assertEquals(listOf("No rental is in progress.", "No rental is in progress."), gleoReplies.map { it.message })
        assertEquals(emptyList(), folder.listFiles { f -> f.isDirectory }!!.toList())
    }

    @Test
    fun `대여 중이면 인수 기록을 다시 열어 달라고 화면에 알린다`() = scenario {
        val c = controller()
        pickUp(c)
        assertEquals(0, c.ui.value.recordRequests)

        c.onGleo(GleoFunction.SHOW_RECORD, "{}")
        runCurrent()
        assertEquals(1, c.ui.value.recordRequests)
        assertEquals(GleoStatus.SUCCESS, gleoReplies.single().status)
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
    }

    @Test
    fun `파라미터를 읽을 수 없으면 아무것도 하지 않고 이유를 답한다`() = scenario {
        val c = controller()
        pickUp(c)
        listOf("not json", "[1]", "{" + "x".repeat(2000) + "}").forEach { raw ->
            gleoReplies.clear()
            c.onGleo(GleoFunction.START_RETURN_CHECK, raw)
            c.onGleo(GleoFunction.SHOW_RECORD, raw)
            runCurrent()
            assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
            assertEquals(0, c.ui.value.recordRequests)
            assertEquals(listOf(GleoStatus.FAIL, GleoStatus.FAIL), gleoReplies.map { it.status })
        }
    }

    @Test
    fun `어떤 값을 넣어 불러도 기록을 확정하거나 무효로 하거나 닫지 않는다`() = scenario {
        val c = controller()
        pickUp(c)
        val pickup = c.ui.value.pickup!!
        val raws = listOf(null, "{}", """{"confirm":true}""", """{"state":"RETURNED"}""", """{"void":"기록 오류"}""", """{"close":true}""")
        repeat(3) {
            GleoFunction.entries.forEach { function -> raws.forEach { c.onGleo(function, it) } }
            runCurrent()
        }
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertNull(c.ui.value.report)
        assertFalse(File(folder, "${pickup.sessionId}/report.json").exists())
        assertFalse(File(folder, "${pickup.sessionId}/void.json").exists())
        assertFalse(File(folder, "${pickup.sessionId}/return.json").exists())
    }

    @Test
    fun `저장된 기록을 믿을 수 없으면 Gleo 호출로 시작하지 않는다`() = scenario {
        val first = controller()
        pickUp(first)
        val pickup = first.ui.value.pickup!!
        viewModels.forEach { it.clear() }
        val file = File(folder, "${pickup.sessionId}/pickup.json")
        file.writeText(file.readText().replace("82.0", "99.0"))

        val second = controller()
        second.onGleo(GleoFunction.START_RETURN_CHECK, "{}")
        second.onGleo(GleoFunction.SHOW_RECORD, "{}")
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(RentalController.RECORD_DAMAGED, second.ui.value.damaged)
        assertEquals(listOf(GleoStatus.FAIL, GleoStatus.FAIL), gleoReplies.map { it.status })
    }

    // 화면 없이 답하기

    private fun desk(source: VehicleSource = vehicle, policy: RentalPolicy = settings.policy) =
        GleoDesk(store, source, policy, parked, { voice }) { gleoReplies += it }

    @Test
    fun `P단이면 지금 고칠 것과 배터리 차이를 앱이 직접 말하고 Gleo에는 말하지 말라고 한다`() = scenario {
        val c = controller()
        pickUp(c)
        vehicle.fake.update { copy(evSocPercent = 61.0).withWindow(Area.FRONT_RIGHT, true) }
        val desk = desk()
        val captions = mutableListOf<String>()
        val watching = launch { desk.spoken.collect { captions += it } }
        runCurrent()

        desk.whatToCheck("{}")
        runCurrent()

        val speech = "반납 전에 1가지를 확인해 주세요. 조수석 창문이 열려 있습니다. 배터리는 인수 때 82퍼센트, 지금 61퍼센트입니다."
        assertEquals(speech to true, voice.said.last())
        assertEquals(listOf(speech), captions)
        val reply = gleoReplies.single()
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals(GleoMode.SILENT, reply.mode)
        assertEquals("Before the return, 1 item(s) need checking: 조수석 창문. 배터리 was 82 percent at pickup and is 61 percent now.", reply.message)
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state, "상태를 바꾸지 않는다")
        watching.cancel()
    }

    @Test
    fun `주행 중이면 앱은 말하지 않고 Gleo가 답하게 한다`() = scenario {
        pickUp(controller())
        parked.value = false
        val said = voice.said.size

        desk().whatToCheck("{}")
        runCurrent()
        assertEquals(said, voice.said.size)
        assertEquals(GleoMode.VOICE, gleoReplies.single().mode)
        assertEquals(GleoStatus.SUCCESS, gleoReplies.single().status)
    }

    @Test
    fun `앱이 말할 수 없으면 Gleo가 답하게 한다`() = scenario {
        pickUp(controller())
        voice.currentEngine.value = VoiceEngine.CAPTION_ONLY
        val said = voice.said.size

        desk().whatToCheck("{}")
        runCurrent()
        assertEquals(said, voice.said.size)
        assertEquals(GleoMode.VOICE, gleoReplies.single().mode)
    }

    @Test
    fun `대여가 없거나 끝났거나 기록을 믿을 수 없으면 이유를 답한다`() = scenario {
        desk().whatToCheck("{}")
        runCurrent()
        assertEquals("No rental is in progress.", gleoReplies.single().message)
        gleoReplies.clear()

        val c = controller()
        pickUp(c)
        val pickup = c.ui.value.pickup!!
        val file = File(folder, "${pickup.sessionId}/pickup.json")
        val original = file.readText()
        file.writeText(original.replace("82.0", "99.0"))
        desk().whatToCheck("{}")
        runCurrent()
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
        assertTrue(gleoReplies.single().message.contains("staff"), gleoReplies.single().message)
        file.writeText(original)
        gleoReplies.clear()

        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        desk().whatToCheck("{}")
        runCurrent()
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
    }

    @Test
    fun `차를 읽지 못하면 7초 전에 실패를 답한다`() = scenario {
        pickUp(controller())
        val stuck = object : VehicleSource {
            override suspend fun state(): VehicleState = awaitCancellation()
            override fun changes(): Flow<VehicleEvent> = emptyFlow()
        }
        val answering = launch { desk(stuck).whatToCheck("{}") }
        advanceTimeBy(5_900)
        runCurrent()
        assertEquals(emptyList(), gleoReplies)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
        assertTrue(answering.isCompleted)
    }

    @Test
    fun `앞 요청을 처리하는 중이면 차를 다시 읽지 않는다`() = scenario {
        pickUp(controller())
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val slow = object : VehicleSource {
            override suspend fun state(): VehicleState {
                reads++
                gate.await()
                return EV_START
            }
            override fun changes(): Flow<VehicleEvent> = emptyFlow()
        }
        val desk = desk(slow)
        val first = launch { desk.whatToCheck("{}") }
        runCurrent()
        desk.whatToCheck("{}")
        assertEquals(1, reads)
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)

        gate.complete(Unit)
        first.join()
        assertEquals(GleoStatus.SUCCESS, gleoReplies.last().status)
    }

    @Test
    fun `읽을 수 없는 파라미터로는 차를 읽지 않는다`() = scenario {
        pickUp(controller())
        val reads = vehicle.reads
        desk().whatToCheck("not json")
        runCurrent()
        assertEquals(reads, vehicle.reads)
        assertEquals(GleoStatus.FAIL, gleoReplies.single().status)
    }

    @Test
    fun `앱이 말한 답은 화면이 떠 있으면 자막으로 보인다`() = scenario {
        val spoken = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val c = controller(spoken = spoken)
        pickUp(c)
        spoken.emit("반납 전에 1가지를 확인해 주세요.")
        runCurrent()
        assertEquals("반납 전에 1가지를 확인해 주세요.", c.ui.value.caption)
    }

    // 말로 답하기

    /** 반납 체크를 시작해 '고칠 것이 없습니다. …남길까요?'까지 말한 상태로 만든다. 아직 말하는 중이다. */
    private fun TestScope.askToRecord(c: RentalController) {
        pickUp(c)
        voice.speakingNow.value = true
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertTrue(voice.said.last().first.endsWith(Voice.RECORD_QUESTION), voice.said.last().first)
    }

    @Test
    fun `안내가 끝난 뒤에만 듣고 듣는 중임을 보여 주고 네면 버튼과 같이 기록한다`() = scenario {
        val listener = FakeReplies().apply { heard = "네"; waitsFull = true }
        val c = controller(replies = listener)
        askToRecord(c)
        assertEquals(1, listener.warmed, "대여가 열릴 때 미리 띄운다")
        assertEquals(0, listener.released)
        assertEquals(0, listener.calls, "말하는 동안에는 듣지 않는다")
        assertFalse(c.ui.value.listening)

        voice.speakingNow.value = false
        runCurrent()
        assertEquals(1, listener.calls)
        assertEquals(5.seconds, listener.within, "5초만 듣는다")
        assertTrue(c.ui.value.listening)
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)

        advanceTimeBy(5_100)
        runCurrent()
        assertFalse(c.ui.value.listening)
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertEquals(1, listener.released, "대여가 끝나면 내린다")
    }

    @Test
    fun `아니요나 뜻이 분명하지 않은 말이나 못 들은 경우에는 기록하지 않고 버튼은 그대로다`() = scenario {
        listOf("아니요", "네 그런데 말이야", "오늘 날씨가 정말 좋네요", "응", null).forEach { said ->
            val listener = FakeReplies().apply { heard = said }
            val c = controller(replies = listener)
            runCurrent()
            if (c.ui.value.state == RentalState.RETURN_CHECK) c.cancelReturnCheck() else if (c.ui.value.state == RentalState.IDLE) pickUp(c)
            runCurrent()
            voice.speakingNow.value = false
            c.startReturnCheck()
            runCurrent()
            advanceTimeBy(3_100)
            runCurrent()
            assertEquals(1, listener.calls, said.toString())
            assertEquals(RentalState.RETURN_CHECK, c.ui.value.state, said.toString())
            assertFalse(c.ui.value.listening)

            c.confirmReturn()
            runCurrent()
            assertEquals(RentalState.RETURNED, c.ui.value.state, "버튼은 그대로다")
            c.close()
            runCurrent()
            viewModels.forEach { it.clear() }
        }
    }

    @Test
    fun `고칠 것이 남아 있으면 듣지 않고 모두 고치면 듣는다`() = scenario {
        val listener = FakeReplies().apply { heard = "아니요" }
        val c = controller(replies = listener)
        pickUp(c)
        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, true) }
        c.startReturnCheck()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(0, listener.calls)

        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, false) }
        runCurrent()
        assertTrue(voice.said.last().first.endsWith(Voice.RECORD_QUESTION))
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(1, listener.calls)
    }

    @Test
    fun `듣는 중에 고칠 것이 새로 생기거나 화면이 사라지거나 출발하면 멈추고 기록하지 않는다`() = scenario {
        val changes: List<Pair<String, (RentalController) -> Unit>> = listOf(
            "새로 열림" to { _ -> vehicle.fake.update { withWindow(Area.FRONT_RIGHT, true) } },
            "화면 사라짐" to { c -> c.setVisible(false) },
            "출발" to { _ -> parked.value = false },
            "나중에" to { c -> c.cancelReturnCheck() },
        )
        changes.forEach { (name, change) ->
            val listener = FakeReplies().apply { heard = "네"; waitsFull = true }
            val c = controller(replies = listener)
            runCurrent()
            if (c.ui.value.state == RentalState.IDLE) pickUp(c)
            c.startReturnCheck()
            runCurrent()
            advanceTimeBy(3_100)
            runCurrent()
            assertTrue(c.ui.value.listening, name)

            change(c)
            runCurrent()
            assertEquals(1, listener.cancelled, name)
            assertFalse(c.ui.value.listening, name)
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(c.ui.value.state != RentalState.RETURNED, name)

            // 다음 경우를 위해 되돌린다.
            parked.value = true
            vehicle.fake.update { withWindow(Area.FRONT_RIGHT, false) }
            runCurrent()
            c.setVisible(true)
            runCurrent()
            if (c.ui.value.state == RentalState.RETURN_CHECK) c.cancelReturnCheck()
            runCurrent()
            viewModels.forEach { it.clear() }
            runCurrent()
        }
    }

    @Test
    fun `답을 듣기 전에 버튼을 누르면 듣기를 멈추고 한 번만 기록한다`() = scenario {
        val listener = FakeReplies().apply { heard = "네"; waitsFull = true }
        val c = controller(replies = listener)
        pickUp(c)
        c.startReturnCheck()
        runCurrent()
        advanceTimeBy(3_100)
        runCurrent()
        assertTrue(c.ui.value.listening)

        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertEquals(1, listener.cancelled)
        assertFalse(c.ui.value.listening)
    }

    @Test
    fun `통화 때문에 질문을 말하지 못했으면 듣지 않는다`() = scenario {
        val listener = FakeReplies().apply { heard = "네" }
        val c = controller(replies = listener)
        pickUp(c)
        voice.interruptedNow = true
        c.startReturnCheck()
        runCurrent()
        assertEquals(0, listener.calls, "통화 상대에게 한 네로 기록이 확정되면 안 된다")
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
    }

    @Test
    fun `말이 끝나지 않으면 마이크를 켜지 않는다`() = scenario {
        val listener = FakeReplies().apply { heard = "네" }
        val c = controller(replies = listener)
        askToRecord(c)
        advanceTimeBy(46_000)
        runCurrent()
        assertEquals(0, listener.calls)
        voice.speakingNow.value = false
        runCurrent()
        assertEquals(0, listener.calls, "시간이 지난 질문에는 듣지 않는다")
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
    }

    @Test
    fun `말할 엔진이 없어도 자막의 질문에 답할 수 있다`() = scenario {
        val listener = FakeReplies().apply { heard = "아니요" }
        val c = controller(replies = listener)
        pickUp(c)
        c.startReturnCheck()
        runCurrent()
        assertEquals(1, listener.calls)
    }

    @Test
    fun `들은 말을 어디에도 저장하지 않는다`() = scenario {
        val listener = FakeReplies().apply { heard = "오늘 비밀번호는 칠칠칠이야" }
        val c = controller(replies = listener)
        pickUp(c)
        c.startReturnCheck()
        runCurrent()
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(1, listener.calls)
        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        folder.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains("칠칠칠"), it.name) }
        assertFalse(c.ui.value.toString().contains("칠칠칠"))
    }

    // 스키마

    @Test
    fun `schema json은 작성 가이드를 따르고 함수와 매니페스트가 맞는다`() {
        val schema = Json.parseToJsonElement(File("src/main/assets/schema.json").readText()).jsonObject
        assertEquals("app.chagok", schema["packageName"]!!.jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_NAME, schema["version"]!!.jsonPrimitive.content)
        assertTrue(schema["appDescription"]!!.jsonPrimitive.content.all { it.code < 128 }, "설명은 영어로 쓴다")

        val calls = schema["actionCalls"]!!.jsonArray.map { it.jsonObject }
        assertTrue(calls.size <= 3, "함수는 3개까지다")
        val functions = calls.map {
            assertEquals("function", it["type"]!!.jsonPrimitive.content)
            it["function"]!!.jsonObject
        }
        assertEquals(GleoFunction.entries.map { it.action }.toSet(), functions.map { it["name"]!!.jsonPrimitive.content }.toSet())

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activityPart = manifest.substringAfter(".MainActivity").substringBefore("</activity>")
        val receiverPart = manifest.substringAfter(".rental.GleoReceiver").substringBefore("</receiver>")
        functions.forEach { f ->
            val name = f["name"]!!.jsonPrimitive.content
            assertTrue(name.matches(Regex("[A-Z_]+")), name)
            val description = f["description"]!!.jsonPrimitive.content
            assertTrue(description.all { it.code < 128 }, description)
            assertEquals(emptyList(), f["parameters"]!!.jsonObject["required"]!!.jsonArray.toList(), "$name: 받을 파라미터가 없다")
            val part = if (f["component"]!!.jsonPrimitive.content == "service") receiverPart else activityPart
            assertTrue("android:name=\"$name\"" in part, "$name: 매니페스트에 수신 action이 있어야 한다")
        }
        // 확정·무효·닫기로 들어오는 입구가 없다.
        listOf("CONFIRM", "VOID", "CLOSE").forEach { assertFalse(Regex("<action android:name=\"[A-Z_]*$it").containsMatchIn(manifest), it) }
    }

    // 내리려 할 때의 안내(T-13)

    private val doorItems = setOf(StateItem.DOORS)

    private fun driverDoor(open: Boolean) = vehicle.fake.update { copy(doorsOpen = doorsOpen + (Area.FRONT_LEFT to open)) }

    private fun TestScope.arrived(c: RentalController) {
        pickUp(c)
        navi.arrivals.tryEmit(Unit)
        runCurrent()
        assertTrue(c.ui.value.arrived)
    }

    @Test
    fun `도착한 뒤 P단에서 운전석 문이 처음 열리면 한 번만 말하고 자막으로 보여 준다`() = scenario {
        val c = controller()
        arrived(c)
        assertTrue(doorItems in vehicle.subscribed, "도착하면 문을 듣는다")
        voice.said.clear()

        driverDoor(true)
        runCurrent()

        assertEquals(listOf(Voice.leaving() to true), voice.said, "끊고 바로 말한다")
        assertEquals(Voice.leaving(), c.ui.value.caption)
        assertFalse(doorItems in vehicle.subscribed, "말한 뒤에는 문을 듣지 않는다")

        // 닫았다 다시 열어도 다시 말하지 않는다.
        driverDoor(false)
        runCurrent()
        driverDoor(true)
        runCurrent()
        assertEquals(1, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `도착하기 전에는 문을 듣지도 말하지도 않는다`() = scenario {
        val c = controller()
        pickUp(c)
        assertFalse(doorItems in vehicle.subscribed)
        driverDoor(true)
        runCurrent()
        driverDoor(false)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `반납 체크를 시작하면 문 듣기를 멈추고 말하지 않는다`() = scenario {
        val c = controller()
        arrived(c)
        c.startReturnCheck()
        runCurrent()
        assertFalse(doorItems in vehicle.subscribed)

        driverDoor(true)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
        assertFalse(c.ui.value.caption == Voice.leaving())
    }

    @Test
    fun `반납 기록을 남긴 뒤에는 말하지 않는다`() = scenario {
        val c = controller()
        arrived(c)
        c.recordStaffReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertFalse(doorItems in vehicle.subscribed)
        driverDoor(true)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `D단에서 문이 열리면 말하지 않고 P단이 된 뒤 처음 열릴 때 말한다`() = scenario {
        val c = controller()
        arrived(c)
        voice.said.clear()
        parked.value = false
        runCurrent()

        driverDoor(true)
        runCurrent()
        assertEquals(emptyList(), voice.said)

        driverDoor(false)
        runCurrent()
        parked.value = true
        runCurrent()
        assertEquals(emptyList(), voice.said, "기어가 바뀐 것만으로는 말하지 않는다")

        driverDoor(true)
        runCurrent()
        assertEquals(listOf(Voice.leaving() to true), voice.said)
    }

    @Test
    fun `P단이 되기 전에 도착하면 P단이 된 뒤에야 문을 읽는다`() = scenario {
        parked.value = false
        val c = controller()
        runCurrent()
        // 인수는 P단에서 하고, 도착은 달리는 중에 온다.
        parked.value = true
        pickUp(c)
        parked.value = false
        runCurrent()
        navi.arrivals.tryEmit(Unit)
        runCurrent()
        assertFalse(doorItems in vehicle.subscribed)

        parked.value = true
        runCurrent()
        assertTrue(doorItems in vehicle.subscribed)
    }

    @Test
    fun `문을 읽지 못하면 말하지 않는다`() = scenario {
        val c = controller()
        pickUp(c)
        vehicle.failures[StateItem.DOORS] = 1
        navi.arrivals.tryEmit(Unit)
        runCurrent()
        assertTrue(c.ui.value.arrived, "도착 안내는 그대로다")
        assertFalse(doorItems in vehicle.subscribed, "지금 문을 모르면 듣지 않는다")

        driverDoor(true)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `이미 열린 문은 닫았다 다시 열 때만 말한다`() = scenario {
        val c = controller()
        pickUp(c)
        driverDoor(true)
        runCurrent()
        navi.arrivals.tryEmit(Unit)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })

        driverDoor(false)
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
        driverDoor(true)
        runCurrent()
        assertEquals(1, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `조수석 문은 내리려는 신호로 보지 않는다`() = scenario {
        val c = controller()
        arrived(c)
        vehicle.fake.update { copy(doorsOpen = doorsOpen + (Area.FRONT_RIGHT to true)) }
        runCurrent()
        assertEquals(0, voice.said.count { it.first == Voice.leaving() })
        assertTrue(doorItems in vehicle.subscribed)
    }

    @Test
    fun `앱이 뒤에 있으면 알림을 다시 띄우고 앞에 있으면 띄우지 않는다`() = scenario {
        val trip = keeper()
        val c = controller(trip = trip)
        c.setVisible(true)
        arrived(c)
        driverDoor(true)
        runCurrent()
        assertEquals(0, notice.shown)
        assertEquals(1, voice.said.count { it.first == Voice.leaving() })
    }

    @Test
    fun `앱이 뒤에 있을 때 문이 열리면 알림이 한 번 더 뜬다`() = scenario {
        val c = controller()
        pickUp(c)
        c.setVisible(false)
        runCurrent()
        navi.arrivals.tryEmit(Unit)
        runCurrent()
        assertEquals(1, notice.shown, "도착 알림")

        driverDoor(true)
        runCurrent()
        assertEquals(2, notice.shown, "내리려 할 때 다시 알린다")
        assertEquals(1, voice.said.count { it.first == Voice.leaving() })
        assertEquals(Voice.leaving(), c.ui.value.caption, "앱으로 돌아오면 자막이 보인다")
    }

    @Test
    fun `문 듣기는 문 이력을 어디에도 남기지 않는다`() = scenario {
        val c = controller()
        val pickup = c.let { arrived(it); it.ui.value.pickup!! }
        driverDoor(true)
        runCurrent()
        val lines = store.readTimeline(pickup.sessionId)
        assertEquals(emptyList(), lines)
        assertFalse(File(folder, "${pickup.sessionId}/timeline.jsonl").exists() && File(folder, "${pickup.sessionId}/timeline.jsonl").readText().contains("door"))
        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        val report = c.ui.value.report!!
        assertEquals(emptyList(), report.timeline)
    }

    // '닫기'와 내비

    @Test
    fun `남은 길안내를 취소하며 닫으면 앱을 앞으로 되돌리고 한 번만 그렇게 한다`() = scenario {
        val c = controller()
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        assertTrue(navi.guiding)
        c.setVisible(true)
        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        assertFalse(c.reclaimFront(), "리포트를 보는 동안에는 되돌릴 일이 없다")
        assertTrue(navi.guiding, "리포트의 QR을 가리지 않게 아직 취소하지 않는다")

        c.close()
        runCurrent()
        assertTrue(c.reclaimFront())
        assertFalse(c.reclaimFront())
    }

    @Test
    fun `길안내가 없거나 앱이 뒤에 있으면 닫아도 앱을 끌어오지 않는다`() = scenario {
        val c = controller()
        pickUp(c)
        c.setVisible(true)
        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        c.close()
        runCurrent()
        assertFalse(c.reclaimFront(), "길안내가 없었다")

        val d = controller()
        pickUp(d)
        d.planReturnTrip()
        runCurrent()
        d.startReturnRoute()
        runCurrent()
        d.setVisible(true)
        d.startReturnCheck()
        runCurrent()
        d.confirmReturn()
        runCurrent()
        d.setVisible(false)
        runCurrent()
        d.close()
        runCurrent()
        assertFalse(d.reclaimFront(), "고객이 다른 앱을 보는 중이다")
    }

    @Test
    fun `도착 재현은 앱이 뒤에 있으면 그 자리에서 길안내를 취소하고 앞에 있으면 닫을 때까지 미룬다`() = scenario {
        val behind = keeper(canSimulate = true)
        val c = controller(trip = behind)
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        c.setVisible(false)
        runCurrent()
        c.simulateArrival()
        runCurrent()
        advanceTimeBy(5_100)
        runCurrent()
        assertTrue(c.ui.value.arrived)
        assertEquals(1, navi.cancels, "내비가 이미 앞에 있어 화면이 바뀌지 않는다")

        c.setVisible(true)
        runCurrent()
        c.startReturnCheck()
        runCurrent()
        c.confirmReturn()
        runCurrent()
        c.close()
        runCurrent()
        assertFalse(c.reclaimFront(), "남은 길안내가 없으니 닫아도 내비가 나오지 않는다")
    }

    @Test
    fun `도착 재현 때 앱이 앞에 있으면 내비가 도착 안내를 가리지 않게 취소를 미룬다`() = scenario {
        val c = controller(trip = keeper(canSimulate = true))
        pickUp(c)
        c.planReturnTrip()
        runCurrent()
        c.startReturnRoute()
        runCurrent()
        c.setVisible(true)
        runCurrent()
        c.simulateArrival()
        runCurrent()
        advanceTimeBy(5_100)
        runCurrent()
        assertTrue(c.ui.value.arrived)
        assertEquals(0, navi.cancels)
        assertTrue(navi.guiding)
    }
}
