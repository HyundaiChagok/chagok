package app.chagok.rental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.chagok.AppSettings
import app.chagok.core.fake.FakeVehicle
import app.chagok.core.fake.withDoor
import app.chagok.core.fake.withLight
import app.chagok.core.fake.withTelltale
import app.chagok.core.fake.withWindow
import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.Unavailable
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.RecordGap
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalPolicy
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.ShareCode
import app.chagok.core.rental.Voice
import app.chagok.core.rental.Wording
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.VoiceEngine
import app.chagok.core.voice.VoiceOutput
import app.chagok.demo.DemoVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 말한 것을 적어 두는 가짜 음성 */
internal class FakeVoice : VoiceOutput {
    val said = mutableListOf<Pair<String, Boolean>>()
    var stops = 0
    val currentEngine = MutableStateFlow(VoiceEngine.PLEOS_TTS)
    val speakingNow = MutableStateFlow(false)
    var interruptedNow = false
    override val speaking: StateFlow<Boolean> = speakingNow
    override val interrupted: Boolean get() = interruptedNow
    override val engine: StateFlow<VoiceEngine> = currentEngine

    override fun say(text: String, flush: Boolean) {
        said += text to flush
    }

    override fun stop() {
        stops++
    }
}

/** 항목별로 읽기 실패를 심을 수 있는 차. failures는 앞으로 몇 번 더 실패할지다. subscribed는 지금 듣고 있는 구독이다(null은 전체). */
internal class TestVehicle(initial: VehicleState = DemoVehicle.START, private val vinValue: String? = null) : VehicleSource {
    val fake = FakeVehicle(initial)
    val failures = mutableMapOf<StateItem, Int>()
    var reads = 0
    var onRead: (Int) -> Unit = {}
    val subscribed = mutableListOf<Set<StateItem>?>()

    override suspend fun vin(): String? = vinValue

    override fun changes(items: Set<StateItem>): Flow<VehicleEvent> = fake.changes(items)
        .onStart { subscribed += items }
        .onCompletion { subscribed -= items }

    override suspend fun state(): VehicleState {
        reads++
        onRead(reads)
        val failing = failures.filterValues { it > 0 }.keys
        failing.forEach { failures[it] = failures.getValue(it) - 1 }
        val state = fake.state().only(StateItem.entries.toSet() - failing)
        return state.copy(unavailable = state.unavailable + failing.associateWith { Unavailable.Failed("TIMEOUT") })
    }

    override fun changes(): Flow<VehicleEvent> = fake.changes()
        .onStart { subscribed += null }
        .onCompletion { subscribed -= null }
}

/**
 * 컨트롤러를 차 없이 돌린다. 앱을 강제 종료하는 대신 ViewModel과 대여 중 기록을 멈추고(코루틴이 모두 멈춘다), 같은 폴더로 새 컨트롤러를 만든다.
 * 화면만 사라지는 경우는 대여 중 기록을 두고 ViewModel만 지운다([closeScreen]).
 * 끝없이 도는 코루틴(생존 시각, 주기 확인)이 있어 advanceUntilIdle은 쓰지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RentalControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val folder: File = Files.createTempDirectory("chagok").toFile()
    private val store = RecordStore(folder)
    private val viewModels = mutableMapOf<RentalController, ViewModelStore>()
    private val processes = mutableListOf<CoroutineScope>()
    private val processOf = mutableMapOf<RentalController, CoroutineScope>()
    private var clock = Instant.parse("2026-11-03T00:12:00Z")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        folder.deleteRecursively()
    }

    private fun controller(
        vehicle: VehicleSource = TestVehicle(),
        voice: VoiceOutput = FakeVoice(),
        parked: Flow<Boolean> = emptyFlow(),
        records: RecordStore = store,
        onClosed: () -> Unit = {},
        onRecording: (Boolean) -> Unit = {},
        settings: AppSettings = AppSettings(reportBaseUrl = "https://example.com/chagok"),
        process: Process = newProcess(vehicle, records),
    ): RentalController {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RentalController(
                vehicle, records, voice, settings, parked, process.timeline, { clock }, onClosed, onRecording, dispatcher,
            ) as T
        }
        val owner = ViewModelStore()
        return ViewModelProvider(owner, factory)[RentalController::class.java].also {
            viewModels[it] = owner
            processOf[it] = process.scope
        }
    }

    /** 프로세스 수준의 대여 중 기록. 앱의 AppGraph에 있는 것과 같다. */
    private class Process(val scope: CoroutineScope, val timeline: TimelineKeeper)

    private fun newProcess(vehicle: VehicleSource = TestVehicle(), records: RecordStore = store): Process {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { processes += it }
        return Process(scope, TimelineKeeper(vehicle, records, scope) { clock })
    }

    /** 프로세스째 꺼진다. */
    private fun kill(controller: RentalController) {
        viewModels.remove(controller)?.clear()
        processOf.remove(controller)?.cancel()
    }

    /** 화면만 사라진다. 프로세스와 대여 중 기록은 그대로다. */
    private fun closeScreen(controller: RentalController) {
        viewModels.remove(controller)?.clear()
        processOf.remove(controller)
    }

    // runTest는 본문이 끝나면 할 일이 없을 때까지 스케줄러를 돌린다. 생존 시각과 주기 확인은 끝이 없으니 그 전에 모두 멈춘다.
    private fun scenario(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            viewModels.values.forEach { it.clear() }
            viewModels.clear()
            processes.forEach { it.cancel() }
            processes.clear()
        }
    }

    private fun TestScope.pickUp(controller: RentalController): SnapshotRecord {
        runCurrent()
        controller.startPickup()
        runCurrent()
        controller.acceptNotice()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, controller.ui.value.state)
        return controller.ui.value.pickup!!
    }

    private fun TestScope.finishReturn(controller: RentalController) {
        controller.startReturnCheck()
        runCurrent()
        controller.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, controller.ui.value.state, controller.ui.value.error)
    }

    private fun sessionFile(sessionId: String, name: String) = File(folder, "$sessionId/$name")

    // 복구

    @Test
    fun `앱을 다시 켜면 말없이 대여 중 화면으로 이어 간다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)

        val voice = FakeVoice()
        val second = controller(voice = voice)
        assertTrue(second.ui.value.restoring, "복원 전에는 입력을 받지 않는다")
        runCurrent()

        val ui = second.ui.value
        assertFalse(ui.restoring)
        assertTrue(ui.restored, "인수 QR 화면을 다시 띄우지 않게 알린다")
        assertEquals(RentalState.IN_RENTAL, ui.state)
        assertEquals(pickup.hash, ui.pickup?.hash)
        assertEquals(pickup.recordNo, ui.pickup?.recordNo)
        assertEquals(ShareCode.url("https://example.com/chagok", pickup), ui.pickupUrl)
        assertNull(ui.caption)
        assertNull(ui.damaged)
        assertEquals(emptyList(), voice.said)
    }

    @Test
    fun `대여가 열려 있으면 새 인수를 시작할 수 없다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)

        val second = controller()
        runCurrent()
        second.startPickup()
        second.acceptNotice()
        runCurrent()

        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(pickup.hash, second.ui.value.pickup?.hash)
        assertEquals(listOf(pickup.sessionId), folder.listFiles { f -> f.isDirectory }!!.map { it.name })
    }

    @Test
    fun `복원이 끝나기 전의 입력은 버린다`() = scenario {
        val idle = controller()
        idle.startPickup()
        runCurrent()
        assertEquals(RentalState.IDLE, idle.ui.value.state, "복원 전에 누른 버튼은 복원 뒤로 미루지 않는다")
        assertFalse(idle.ui.value.restoring)

        idle.startPickup()
        runCurrent()
        assertEquals(RentalState.NOTICE, idle.ui.value.state)
    }

    @Test
    fun `반납 체크나 반납 기록 중에 꺼졌으면 대여 중으로 돌아온다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)

        for (state in listOf(RentalState.RETURN_CHECK, RentalState.RETURN)) {
            store.saveSession(SavedSession(state, pickup.sessionId, pickup.recordNo, clock))
            val voice = FakeVoice()
            val again = controller(voice = voice)
            runCurrent()
            assertEquals(RentalState.IN_RENTAL, again.ui.value.state, state.name)
            assertEquals(pickup.hash, again.ui.value.pickup?.hash)
            assertEquals(emptyList(), again.ui.value.check)
            assertEquals(emptyList(), voice.said)
            assertEquals(RentalState.IN_RENTAL, assertIs<Stored.Found<SavedSession>>(store.readSession()).value.state)
            kill(again)
        }
    }

    @Test
    fun `고지나 인수 기록 중에 꺼졌으면 대기로 돌아간다`() = scenario {
        for (state in listOf(RentalState.NOTICE, RentalState.PICKUP)) {
            store.saveSession(SavedSession(state, null, null, clock))
            val again = controller()
            runCurrent()
            assertEquals(RentalState.IDLE, again.ui.value.state, state.name)
            assertFalse(again.ui.value.restoring)
            kill(again)
        }
    }

    @Test
    fun `반납을 마치고 닫기 전에 꺼졌으면 리포트와 QR을 되살린다`() = scenario {
        val first = controller()
        pickUp(first)
        finishReturn(first)
        val report = first.ui.value.report!!
        val url = first.ui.value.reportUrl
        kill(first)

        val voice = FakeVoice()
        val second = controller(voice = voice)
        runCurrent()

        val ui = second.ui.value
        assertEquals(RentalState.RETURNED, ui.state)
        assertEquals(report.hash, ui.report?.hash)
        assertEquals(url, ui.reportUrl)
        assertEquals(emptyList(), voice.said)

        second.close()
        runCurrent()
        assertEquals(RentalState.IDLE, second.ui.value.state)
        kill(second)

        val third = controller()
        runCurrent()
        assertEquals(RentalState.IDLE, third.ui.value.state, "닫은 대여는 다시 나오지 않는다")
    }

    @Test
    fun `무효 처리하고 닫기 전에 꺼졌으면 무효 화면을 되살린다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        first.void("차량 교체")
        runCurrent()
        assertEquals(RentalState.VOIDED, first.ui.value.state)
        assertTrue(sessionFile(pickup.sessionId, "void.json").readText().contains("차량 교체"))
        kill(first)

        val second = controller()
        runCurrent()
        assertEquals(RentalState.VOIDED, second.ui.value.state)
        assertNull(second.ui.value.report)
    }

    // 손상된 기록

    @Test
    fun `인수 기록 내용이 무결성 값과 다르면 오류를 보여 주고 덮어쓰지 않는다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)
        val file = sessionFile(pickup.sessionId, "pickup.json")
        val tampered = file.readText().replace("82.0", "99.0")
        assertNotEquals(file.readText(), tampered)
        file.writeText(tampered)

        val voice = FakeVoice()
        val second = controller(voice = voice)
        runCurrent()

        val ui = second.ui.value
        assertEquals(RentalState.IN_RENTAL, ui.state, "대기로 돌아가면 새 인수가 생긴다")
        assertNull(ui.pickup)
        assertEquals(RentalController.RECORD_DAMAGED, ui.damaged)
        assertEquals(emptyList(), voice.said)

        // 새 인수도, 반납 체크도 되지 않는다.
        second.startPickup()
        second.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(tampered, file.readText())

        // 직원 무효 처리로만 풀린다. 손상된 파일은 그대로 남는다.
        second.void("기록 오류")
        runCurrent()
        assertEquals(RentalState.VOIDED, second.ui.value.state)
        assertNull(second.ui.value.damaged)
        assertTrue(sessionFile(pickup.sessionId, "void.json").exists(), "인수 기록을 못 읽어도 대여에 무효를 남긴다")
        second.close()
        runCurrent()
        assertEquals(RentalState.IDLE, second.ui.value.state)

        val next = pickUp(second)
        assertNotEquals(pickup.sessionId, next.sessionId)
        assertEquals(tampered, file.readText())
    }

    @Test
    fun `무결성 값을 지웠거나 잘렸거나 없어진 기록도 믿지 않는다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)
        val file = sessionFile(pickup.sessionId, "pickup.json")
        val original = file.readText()

        val damages = listOf<() -> Unit>(
            { file.writeText(original.replace(Regex(""","hash":"[^"]+"""), "")) },
            { file.writeText(original.take(original.length / 2)) },
            { file.writeText("") },
            { file.delete() },
            { file.writeText(original.replace("0007", "0008")) },
        )
        damages.forEachIndexed { i, damage ->
            file.writeText(original)
            damage()
            val again = controller()
            runCurrent()
            assertEquals(RentalController.RECORD_DAMAGED, again.ui.value.damaged, "경우 $i")
            assertEquals(RentalState.IN_RENTAL, again.ui.value.state, "경우 $i")
            kill(again)
        }
    }

    @Test
    fun `다른 대여의 기록을 가리키면 믿지 않는다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        kill(first)
        val other = "other-session"
        sessionFile(pickup.sessionId, "pickup.json").copyTo(sessionFile(other, "pickup.json"))
        store.saveSession(SavedSession(RentalState.IN_RENTAL, other, pickup.recordNo, clock))

        val second = controller()
        runCurrent()
        assertEquals(RentalController.RECORD_DAMAGED, second.ui.value.damaged)
    }

    @Test
    fun `session json이 깨졌으면 대기로 보지 않고 직원에게 넘긴다`() = scenario {
        File(folder, "session.json").writeText("""{"state":"IN_REN""")

        val second = controller()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(RentalController.RECORD_DAMAGED, second.ui.value.damaged)

        second.void("기록 오류")
        runCurrent()
        second.close()
        runCurrent()
        assertEquals(RentalState.IDLE, second.ui.value.state)
    }

    @Test
    fun `저장된 리포트가 맞지 않으면 리포트 없이 오류만 보여 주고 닫을 수 있다`() = scenario {
        val first = controller()
        val pickup = pickUp(first)
        finishReturn(first)
        kill(first)
        val file = sessionFile(pickup.sessionId, "report.json")
        val damaged = file.readText().replace("0007", "0008")
        file.writeText(damaged)

        val second = controller()
        runCurrent()
        assertEquals(RentalState.RETURNED, second.ui.value.state)
        assertNull(second.ui.value.report)
        assertEquals(RentalController.RECORD_DAMAGED, second.ui.value.damaged)

        second.close()
        runCurrent()
        assertEquals(RentalState.IDLE, second.ui.value.state)
        assertEquals(damaged, file.readText())
    }

    @Test
    fun `화면에 보이는 오류 문구에 금지어가 없다`() {
        listOf(
            RentalController.PICKUP_READ_FAILED, RentalController.SAVE_FAILED,
            RentalController.RECORD_DAMAGED, RentalController.READ_RECORD_FAILED,
        ).forEach { assertEquals(emptyList(), Wording.violations(it), it) }
    }

    // 부분 읽기 실패

    @Test
    fun `일부 항목만 못 읽으면 1초 간격으로 그 항목을 다시 읽어 채운다`() = scenario {
        // 시연 모드처럼 시나리오 값은 늘 있고 창문만 실패한다. 예전에는 재시도 없이 그대로 기록됐다.
        val vehicle = TestVehicle()
        vehicle.failures[StateItem.WINDOWS] = 2
        val c = controller(vehicle)
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        assertEquals(RentalState.PICKUP, c.ui.value.state)
        assertEquals(1, vehicle.reads)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, vehicle.reads)
        assertEquals(RentalState.PICKUP, c.ui.value.state)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, vehicle.reads)
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        val state = c.ui.value.pickup!!.state
        assertEquals(DemoVehicle.START.windowsOpen, state.windowsOpen)
        assertNull(state.missing(StateItem.WINDOWS))
    }

    @Test
    fun `다시 읽어도 앞에서 읽은 값을 버리지 않는다`() = scenario {
        val vehicle = TestVehicle()
        vehicle.failures[StateItem.WINDOWS] = 1
        vehicle.onRead = { n -> if (n == 2) vehicle.failures[StateItem.FUEL] = 9 } // 두 번째에는 연료를 못 읽는다.
        val c = controller(vehicle)
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        val state = c.ui.value.pickup!!.state
        assertEquals(2, vehicle.reads)
        assertEquals(DemoVehicle.START.fuelPercent, state.fuelPercent)
        assertEquals(DemoVehicle.START.windowsOpen, state.windowsOpen)
    }

    @Test
    fun `세 번 다시 읽어도 못 읽으면 그 항목만 실패로 기록한다`() = scenario {
        val vehicle = TestVehicle()
        vehicle.failures[StateItem.WINDOWS] = Int.MAX_VALUE
        val c = controller(vehicle)
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        repeat(3) {
            assertEquals(RentalState.PICKUP, c.ui.value.state)
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertEquals(4, vehicle.reads)
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        val pickup = c.ui.value.pickup!!
        assertEquals(Unavailable.Failed("TIMEOUT"), pickup.state.missing(StateItem.WINDOWS))
        assertEquals(DemoVehicle.START.fuelPercent, pickup.state.fuelPercent)
        val saved = sessionFile(pickup.sessionId, "pickup.json").readText()
        assertTrue(saved.contains(""""windowsOpen":{"reason":"TIMEOUT","status":"FAILED"}"""), saved)
    }

    @Test
    fun `지원하지 않는 항목은 다시 읽지 않는다`() = scenario {
        val vehicle = TestVehicle() // 시작 상태에 배터리·실내등이 '지원 안 함'으로 있다.
        pickUp(controller(vehicle))
        assertEquals(1, vehicle.reads)
    }

    @Test
    fun `하나도 못 읽으면 인수 기록을 남기지 않고 고지로 돌아간다`() = scenario {
        val vehicle = TestVehicle()
        Rental.RECORDED.forEach { vehicle.failures[it] = Int.MAX_VALUE }
        val c = controller(vehicle)
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertEquals(RentalController.PICKUP_READ_FAILED, c.ui.value.error)
        assertNull(c.ui.value.pickup)
        assertEquals(emptyList(), folder.listFiles { f -> f.isDirectory }!!.toList())
    }

    @Test
    fun `읽기가 시간 제한까지 걸리면 10초 안에 끝나도록 다시 읽기를 줄인다`() = scenario {
        val vehicle = TestVehicle()
        vehicle.failures[StateItem.WINDOWS] = Int.MAX_VALUE
        vehicle.onRead = { clock = clock.plusSeconds(4) }
        val c = controller(vehicle)
        runCurrent()
        c.startPickup()
        runCurrent()
        c.acceptNotice()
        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertEquals(2, vehicle.reads)
    }

    @Test
    fun `반납 체크 중 못 읽은 창문은 읽지 못함으로 보이고 반납 기록에 실패로 남는다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val c = controller(vehicle, voice)
        pickUp(c)
        vehicle.failures[StateItem.WINDOWS] = Int.MAX_VALUE

        c.startReturnCheck()
        runCurrent()
        assertEquals(listOf<CheckItem>(CheckItem.Unreadable(StateItem.WINDOWS)), c.ui.value.check)
        assertEquals("반납 전에 1가지를 확인해 주세요. 창문을 읽지 못했습니다. 직접 확인해 주세요.", voice.said.last().first)

        c.confirmReturn()
        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        val row = c.ui.value.report!!.comparison.rows.single { it.item == StateItem.WINDOWS }
        assertEquals(app.chagok.core.inspect.Verdict.UNKNOWN, row.verdict)
    }

    // 정차 해제와 수명 주기

    @Test
    fun `반납 체크 중 기어가 P에서 벗어나면 대여 중으로 돌아가고 말을 멈춘다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val parked = MutableStateFlow(true)
        val c = controller(vehicle, voice, parked)
        pickUp(c)
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertTrue(voice.said.isNotEmpty())
        val stops = voice.stops
        val said = voice.said.size

        parked.value = false
        runCurrent()

        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertFalse(c.ui.value.parked)
        assertTrue(voice.stops > stops, "말하던 안내를 멈춘다")
        assertNull(c.ui.value.caption)
        assertEquals(emptyList(), c.ui.value.check)

        // 주기 확인도 멈추고 더 말하지 않는다.
        val reads = vehicle.reads
        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, true) }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(reads, vehicle.reads)
        assertEquals(said, voice.said.size)

        // 주행 중에는 반납 체크를 시작할 수 없다.
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)

        parked.value = true
        runCurrent()
        c.startReturnCheck()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
    }

    @Test
    fun `정차가 풀리면 인수 안내도 멈추고 주행 중에는 말하지 않는다`() = scenario {
        val voice = FakeVoice()
        val parked = MutableStateFlow(true)
        val c = controller(voice = voice, parked = parked)
        pickUp(c)
        val stops = voice.stops

        parked.value = false
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertTrue(voice.stops > stops)
    }

    @Test
    fun `정차 중이 아니면 인수를 시작할 수 없다`() = scenario {
        val c = controller(parked = MutableStateFlow(false))
        runCurrent()
        c.startPickup()
        runCurrent()
        assertEquals(RentalState.IDLE, c.ui.value.state)
    }

    @Test
    fun `화면이 보이지 않으면 주기 확인을 멈추고 돌아오면 달라진 것만 말한다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val c = controller(vehicle, voice)
        pickUp(c)
        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, true) }
        c.startReturnCheck()
        runCurrent()
        assertEquals(listOf<CheckItem>(CheckItem.WindowOpen(Area.FRONT_RIGHT)), c.ui.value.check)

        c.setVisible(false)
        runCurrent()
        val reads = vehicle.reads
        val said = voice.said.size
        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, false) }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(reads, vehicle.reads, "보이지 않는 동안에는 읽지 않는다")
        assertEquals(said, voice.said.size)
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)

        c.setVisible(true)
        runCurrent()
        assertTrue(vehicle.reads > reads)
        assertEquals(emptyList(), c.ui.value.check)
        assertEquals("조수석 창문이 닫혔습니다. 고칠 것은 모두 끝났습니다. 반납 기록을 남길까요?", voice.said.last().first)
        assertEquals(said + 1, voice.said.size, "요약을 다시 말하지 않는다")

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vehicle.reads > reads + 1, "다시 주기 확인을 한다")
    }

    @Test
    fun `화면이 보이지 않아도 대여 중 타임라인은 이어 간다`() = scenario {
        val vehicle = TestVehicle()
        val c = controller(vehicle)
        val pickup = pickUp(c)
        c.setVisible(false)
        runCurrent()

        vehicle.fake.update { withTelltale(Telltale("TPMS", "타이어 공기압")) }
        runCurrent()

        assertEquals(1, sessionFile(pickup.sessionId, "timeline.jsonl").readLines().size)
    }

    // 음성 정책

    @Test
    fun `상태가 바뀌면 이전 안내를 끊는다`() = scenario {
        val voice = FakeVoice()
        val c = controller(voice = voice)
        runCurrent()
        var stops = voice.stops

        fun TestScope.step(action: () -> Unit) {
            action()
            runCurrent()
            assertTrue(voice.stops > stops, "전이마다 끊는다")
            stops = voice.stops
        }
        step { c.startPickup() }
        assertEquals(Voice.notice(), voice.said.last().first)
        step { c.acceptNotice() }
        assertTrue(voice.said.last().first.startsWith("인수 기록을 남겼습니다."))
        step { c.startReturnCheck() }
        step { c.cancelReturnCheck() }
        step { c.startReturnCheck() }
        step { c.confirmReturn() }
        assertEquals(Voice.returnDone(), voice.said.last().first)
        step { c.close() }
    }

    @Test
    fun `고침 안내는 앞 안내를 끊고 바로 말한다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val c = controller(vehicle, voice)
        pickUp(c)
        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, true).withLight(StateItem.TAIL_LAMP, true).withDoor(Area.REAR_LEFT, true) }
        c.startReturnCheck()
        runCurrent()
        assertEquals("반납 전에 3가지를 확인해 주세요. 조수석 창문이 열려 있고, 뒷좌석 왼쪽 문이 열려 있고, 미등이 켜져 있습니다." to false, voice.said.last())

        vehicle.fake.update { withWindow(Area.FRONT_RIGHT, false) }
        runCurrent()
        assertEquals("조수석 창문이 닫혔습니다. 뒷좌석 왼쪽 문과 미등이 남았습니다." to true, voice.said.last())

        // 한 번에 둘을 고치면 앞의 안내를 지우지 않는다.
        val said = voice.said.size
        vehicle.fake.update { withDoor(Area.REAR_LEFT, false).withLight(StateItem.TAIL_LAMP, false) }
        runCurrent()
        assertEquals(
            listOf(
                "뒷좌석 왼쪽 문이 닫혔습니다. 고칠 것은 모두 끝났습니다. 반납 기록을 남길까요?" to true,
                "미등이 꺼졌습니다. 고칠 것은 모두 끝났습니다. 반납 기록을 남길까요?" to false,
            ),
            voice.said.drop(said),
        )

        // 새로 열린 것은 앞 안내 뒤에 말한다.
        vehicle.fake.update { withDoor(Area.FRONT_LEFT, true) }
        runCurrent()
        assertEquals("운전석 문이 열렸습니다." to false, voice.said.last())
    }

    @Test
    fun `화면만 사라졌다 돌아오면 공백 없이 같은 기록을 잇고 그 사이 변화도 남는다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val process = newProcess(vehicle)
        val first = controller(vehicle, process = process)
        val pickup = pickUp(first)
        val timeline = sessionFile(pickup.sessionId, "timeline.jsonl")

        // 뒤로 가기로 화면이 사라져도 기록은 프로세스에서 돈다.
        closeScreen(first)
        repeat(5) {
            clock = clock.plusSeconds(60)
            advanceTimeBy(60_000)
            runCurrent()
        }
        vehicle.fake.update { withTelltale(tpms) }
        runCurrent()
        assertEquals(1, timeline.readLines().size, "화면이 없어도 경고등이 남는다")

        // 런처에서 다시 열면 복구가 돌지만 공백을 쓰지 않고 이벤트도 두 번 쓰지 않는다.
        val second = controller(vehicle, process = process)
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(1, timeline.readLines().size, timeline.readText())

        clock = clock.plusSeconds(60)
        finishReturn(second)
        val report = second.ui.value.report!!
        assertEquals(emptyList(), report.gaps)
        assertEquals(listOf("경고등 켜짐: 타이어 공기압"), report.timeline.map { it.note })
    }

    @Test
    fun `서비스가 먼저 이어 갔으면 화면 복구가 공백을 두 번 쓰지 않는다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val first = controller(vehicle)
        val pickup = pickUp(first)
        val timeline = sessionFile(pickup.sessionId, "timeline.jsonl")
        kill(first)

        // 재부팅: 서비스가 화면 없이 대여를 잇는다.
        val rebootAt = clock.plusSeconds(7_200)
        clock = rebootAt
        val process = newProcess(vehicle)
        assertTrue(process.timeline.resumeSaved())
        runCurrent()
        assertEquals(listOf(RecordGap(pickup.capturedAt, rebootAt)), process.timeline.gaps)

        // 화면을 열기 전에 경고등이 켜졌다.
        clock = clock.plusSeconds(300)
        vehicle.fake.update { withTelltale(tpms) }
        runCurrent()
        assertEquals(2, timeline.readLines().size, "공백 한 줄, 경고등 한 줄")

        // 나중에 앱을 열어도 공백은 그대로 한 줄이다. 서비스를 다시 불러도 마찬가지다.
        val second = controller(vehicle, process = process)
        runCurrent()
        assertTrue(process.timeline.resumeSaved())
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(2, timeline.readLines().size, timeline.readText())
        assertEquals(listOf(RecordGap(pickup.capturedAt, rebootAt)), process.timeline.gaps)

        finishReturn(second)
        val report = second.ui.value.report!!
        assertEquals(listOf(RecordGap(pickup.capturedAt, rebootAt)), report.gaps)
        assertEquals(listOf("경고등 켜짐: 타이어 공기압"), report.timeline.map { it.note })
    }

    @Test
    fun `대여가 열려 있을 때만 서비스를 켜 둔다`() = scenario {
        val calls = mutableListOf<Boolean>()
        val vehicle = TestVehicle()
        val first = controller(vehicle, onRecording = { calls += it })
        runCurrent()
        assertEquals(emptyList(), calls, "대기 중에는 돌지 않는다")

        pickUp(first)
        assertEquals(listOf(true), calls)
        finishReturn(first)
        assertEquals(listOf(true, false), calls)
        first.close()
        runCurrent()

        // 앱을 다시 켜도 열린 대여가 없으면 켜지 않는다.
        kill(first)
        calls.clear()
        val second = controller(vehicle, onRecording = { calls += it })
        runCurrent()
        assertEquals(emptyList(), calls)

        // 앱을 다시 켜서 대여를 이어 가면 켜고, 무효 처리하면 끈다.
        pickUp(second)
        kill(second)
        calls.clear()
        val third = controller(vehicle, onRecording = { calls += it })
        runCurrent()
        assertEquals(listOf(true), calls)
        third.void("기록 오류")
        runCurrent()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `이을 대여가 없으면 서비스가 잇지 않는다`() = scenario {
        val vehicle = TestVehicle()
        assertFalse(newProcess(vehicle).timeline.resumeSaved(), "기록이 없다")

        val c = controller(vehicle)
        pickUp(c)
        finishReturn(c)
        val afterReturn = newProcess(vehicle)
        assertFalse(afterReturn.timeline.resumeSaved(), "반납을 마쳤다")
        assertEquals(null, afterReturn.timeline.events)
    }

    @Test
    fun `대여 중에는 기록할 항목만 듣고 반납 체크 동안에만 전체를 듣는다`() = scenario {
        val vehicle = TestVehicle()
        val c = controller(vehicle)
        pickUp(c)
        assertEquals(listOf<Set<StateItem>?>(Rental.WATCHED_DURING_RENTAL), vehicle.subscribed)

        c.startReturnCheck()
        runCurrent()
        assertTrue(null in vehicle.subscribed, "반납 체크는 전체를 듣는다")

        // 화면이 뒤로 가면 반납 체크의 구독과 주기 확인이 멈추고 기록 구독만 남는다.
        c.setVisible(false)
        runCurrent()
        assertEquals(listOf<Set<StateItem>?>(Rental.WATCHED_DURING_RENTAL), vehicle.subscribed)
        val reads = vehicle.reads
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(reads, vehicle.reads, "화면이 뒤에 있으면 차를 읽지 않는다")

        c.setVisible(true)
        runCurrent()
        assertTrue(null in vehicle.subscribed)
    }

    @Test
    fun `차대번호를 읽으면 기록에 쓰고 화면에는 끝 6자리만 보인다`() = scenario {
        val vin = "KMHC851HFNU123456"
        val c = controller(TestVehicle(vinValue = vin))
        runCurrent()
        assertEquals("…123456", c.ui.value.vehicle)
        assertTrue(c.ui.value.vinRead)

        val pickup = pickUp(c)
        assertEquals(vin, pickup.vehicleId)
        assertFalse(pickup.vehicleIdFallback)
        assertTrue(pickup.recordNo.startsWith("3456-"), pickup.recordNo)
        assertEquals("…123456", c.ui.value.vehicle, "대여 중에도 그대로다")

        finishReturn(c)
        c.close()
        runCurrent()
        assertEquals("…123456", c.ui.value.vehicle, "닫아도 차는 그대로다")
    }

    @Test
    fun `차대번호를 못 읽으면 업체 번호로 기록하고 그 사실을 기록에 남긴다`() = scenario {
        val c = controller()
        runCurrent()
        assertEquals("V-0007", c.ui.value.vehicle)
        assertFalse(c.ui.value.vinRead)

        val pickup = pickUp(c)
        assertEquals("V-0007", pickup.vehicleId)
        assertTrue(pickup.vehicleIdFallback)
        assertTrue(Canonical.encode(pickup.toJson()).contains(""""noVin":true"""))
        finishReturn(c)
        assertTrue(Canonical.encode(c.ui.value.report!!.toJson()).contains(""""noVin":true"""))
    }

    @Test
    fun `에뮬레이터 값은 기록에 출처가 남는다`() = scenario {
        val c = controller(settings = AppSettings(reportBaseUrl = "https://example.com/chagok", recordSource = SnapshotRecord.SOURCE_EMULATOR))
        val pickup = pickUp(c)
        assertEquals(SnapshotRecord.SOURCE_EMULATOR, pickup.source)
        assertFalse(pickup.demo, "시나리오 값이 섞이지 않았다")
        finishReturn(c)
        assertTrue(Canonical.encode(c.ui.value.report!!.toJson()).contains(""""source":"emulator""""))
    }

    @Test
    fun `대여 중 변화는 말하지 않는다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val c = controller(vehicle, voice)
        pickUp(c)
        val said = voice.said.size

        vehicle.fake.update { withTelltale(Telltale("TPMS", "타이어 공기압")) }
        runCurrent()

        assertEquals(said, voice.said.size)
    }

    // 대여 중 타임라인

    @Test
    fun `대여 중 변화는 바로 저장하고 꺼졌던 구간은 공백으로 남겨 리포트에 담는다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val first = controller(vehicle)
        val pickup = pickUp(first)
        val timeline = sessionFile(pickup.sessionId, "timeline.jsonl")
        assertFalse(timeline.exists(), "변화가 없으면 남길 것도 없다")

        // 구독 직후 같은 값이 와도 남기지 않는다.
        vehicle.fake.update { copy(telltales = telltales) }
        runCurrent()
        assertFalse(timeline.exists())

        // 경고등이 켜지면 바로 파일에 있다. 문 열림은 남기지 않는다.
        val litAt = clock.plusSeconds(30)
        clock = litAt
        vehicle.fake.update { withTelltale(tpms).withDoor(Area.FRONT_LEFT, true) }
        runCurrent()
        assertEquals(1, timeline.readLines().size)
        assertTrue(timeline.readText().contains("TPMS"))
        assertFalse(timeline.readText().contains("DOOR"))

        // 생존 시각은 분 단위로 적는다.
        val aliveAt = pickup.capturedAt.plusSeconds(60)
        clock = aliveAt
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(aliveAt.toString(), sessionFile(pickup.sessionId, "alive.txt").readText())

        // 앱이 꺼져 있던 구간이 공백이 된다. 다시 켜면 켜진 경고등을 다시 말하지 않고 이어 간다.
        kill(first)
        val restartAt = Instant.parse("2026-11-04T23:10:00Z")
        clock = restartAt
        val second = controller(vehicle)
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        vehicle.fake.update { copy(telltales = telltales) }
        runCurrent()
        assertEquals(2, timeline.readLines().size, timeline.readText())

        val offAt = Instant.parse("2026-11-05T01:00:00Z")
        clock = offAt
        vehicle.fake.update { withTelltale(tpms, false) }
        runCurrent()

        clock = Instant.parse("2026-11-05T08:40:00Z")
        finishReturn(second)
        val report = second.ui.value.report!!
        assertEquals(
            listOf(litAt to "경고등 켜짐: 타이어 공기압", offAt to "경고등 꺼짐: 타이어 공기압"),
            report.timeline.map { it.at to it.note },
        )
        assertEquals(listOf(RecordGap(aliveAt, restartAt)), report.gaps)
        assertTrue(Canonical.encode(report.toJson()).contains(""""gaps":[{"from":"2026-11-03T00:13:00Z","to":"2026-11-04T23:10:00Z"}]"""))

        // 다시 켜도 같은 리포트다.
        kill(second)
        val third = controller(vehicle)
        runCurrent()
        assertEquals(report.hash, third.ui.value.report?.hash)
        assertEquals(report.timeline, third.ui.value.report?.timeline)
    }

    @Test
    fun `여러 번 꺼져도 공백이 겹치지 않고 잘린 줄 때문에 다음 줄을 잃지 않는다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val first = controller(vehicle)
        val pickup = pickUp(first)
        kill(first)
        val timeline = sessionFile(pickup.sessionId, "timeline.jsonl")

        clock = clock.plusSeconds(600)
        val second = controller(vehicle)
        runCurrent()
        kill(second)
        timeline.appendText("""{"at":"2026-11-03T00:25:00Z","type":"tell""") // 쓰다 꺼진 줄

        clock = clock.plusSeconds(600)
        val third = controller(vehicle)
        runCurrent()
        clock = clock.plusSeconds(60)
        vehicle.fake.update { withTelltale(tpms) }
        runCurrent()

        finishReturn(third)
        val report = third.ui.value.report!!
        assertEquals(listOf("경고등 켜짐: 타이어 공기압"), report.timeline.map { it.note })
        val start = pickup.capturedAt
        assertEquals(listOf(RecordGap(start, start.plusSeconds(1_200))), report.gaps, "맞닿은 공백은 하나로 합친다")
    }

    @Test
    fun `시계가 거꾸로 가면 공백을 만들지 않는다`() = scenario {
        val first = controller()
        pickUp(first)
        kill(first)

        clock = clock.minusSeconds(3_600)
        val second = controller()
        runCurrent()
        finishReturn(second)
        assertEquals(emptyList(), second.ui.value.report!!.gaps)
    }

    @Test
    fun `절전으로 앱이 멈췄던 구간도 공백으로 남긴다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)

        // 프로세스는 살아 있지만 다음 생존 시각까지 시계가 크게 뛰었다.
        clock = clock.plusSeconds(3 * 3_600)
        advanceTimeBy(60_000)
        runCurrent()

        finishReturn(c)
        assertEquals(listOf(RecordGap(pickup.capturedAt, clock)), c.ui.value.report!!.gaps)
    }

    // 디스크 오류

    @Test
    fun `디스크 오류로 앱이 죽지 않고 화면에 오류를 보여 준다`() = scenario {
        // 폴더 자리에 파일이 있어 무엇도 쓸 수 없다.
        val blocked = File(folder, "blocked").apply { writeText("") }
        val c = controller(records = RecordStore(blocked))
        runCurrent()
        assertEquals(RentalState.IDLE, c.ui.value.state)

        c.startPickup()
        runCurrent()
        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertEquals(RentalController.SAVE_FAILED, c.ui.value.error)

        c.acceptNotice()
        runCurrent()
        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertEquals(RentalController.SAVE_FAILED, c.ui.value.error)
        assertNull(c.ui.value.pickup)
    }

    @Test
    fun `인수 기록을 저장하지 못하면 대여를 시작하지 않는다`() = scenario {
        val vehicle = TestVehicle()
        val voice = FakeVoice()
        val c = controller(vehicle, voice)
        runCurrent()
        c.startPickup()
        runCurrent()
        // session.json만 쓸 수 없게 임시 파일 자리를 막는다. 비어 있지 않아 쓰기가 실패해도 지워지지 않는다.
        val blocker = File(folder, "session.json.tmp").apply { mkdirs() }
        File(blocker, "x").writeText("")

        c.acceptNotice()
        runCurrent()

        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertEquals(RentalController.SAVE_FAILED, c.ui.value.error)
        assertNull(c.ui.value.pickup)
        assertFalse(voice.said.last().first.startsWith("인수 기록을 남겼습니다."), "저장하지 못한 기록을 남겼다고 말하지 않는다")

        // 저장할 수 있게 되면 다시 시도할 수 있다.
        blocker.deleteRecursively()
        c.acceptNotice()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)
        assertNull(c.ui.value.error)
    }

    @Test
    fun `반납 기록을 저장하지 못하면 반납 체크로 돌아가 타임라인을 잃지 않고 다시 시도한다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val c = controller(vehicle)
        val pickup = pickUp(c)
        val blocker = sessionFile(pickup.sessionId, "report.json.tmp").apply { mkdirs() }
        c.startReturnCheck()
        runCurrent()

        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertEquals(RentalController.SAVE_FAILED, c.ui.value.error)

        // 저장하지 못한 동안에도 기록은 이어진다.
        vehicle.fake.update { withTelltale(tpms) }
        runCurrent()

        blocker.deleteRecursively()
        clock = clock.plusSeconds(5)
        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertNull(c.ui.value.error)
        val report = c.ui.value.report!!
        assertEquals(listOf("경고등 켜짐: 타이어 공기압"), report.timeline.map { it.note })
        assertEquals(report.hash, (store.readReport(pickup.sessionId) as Stored.Found).value.hash)
        assertTrue(sessionFile(pickup.sessionId, "return.abandoned-1.json").exists(), "먼저 저장된 시도는 지우지 않고 옮긴다")
    }

    @Test
    fun `반납 기록을 저장하다 꺼졌으면 대여 중으로 돌아와 새 기록과 맞는 리포트를 남긴다`() = scenario {
        val first = controller()
        pickUp(first)
        finishReturn(first)
        val abandoned = first.ui.value.report!!
        kill(first)
        // 파일은 다 썼지만 상태를 RETURNED로 바꾸기 전에 꺼진 경우
        store.saveSession(SavedSession(RentalState.RETURN, abandoned.pickup.sessionId, abandoned.recordNo, clock))

        clock = clock.plusSeconds(60)
        val second = controller()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        finishReturn(second)

        val report = second.ui.value.report!!
        assertNotEquals(abandoned.hash, report.hash)
        assertEquals(report.hash, (store.readReport(report.pickup.sessionId) as Stored.Found).value.hash)
        assertEquals(report.returned.hash, (store.readReturn(report.pickup.sessionId) as Stored.Found).value.hash)
    }

    // 저장된 기록

    @Test
    fun `닫은 뒤에도 저장된 기록으로 같은 QR을 다시 만든다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        finishReturn(c)
        val report = c.ui.value.report!!
        val url = c.ui.value.reportUrl
        c.close()
        runCurrent()
        assertNull(c.ui.value.report)

        assertTrue(c.unlockRecords("2580"))
        runCurrent()
        val entry = c.ui.value.records.single()
        assertEquals(pickup.sessionId, entry.sessionId)
        assertEquals(pickup.recordNo, entry.recordNo)
        assertEquals(StoredSession.Status.RETURNED, entry.status)

        c.openRecord(entry.sessionId)
        runCurrent()
        val opened = assertNotNull(c.ui.value.opened)
        assertEquals(report.hash, opened.hash)
        assertEquals(url, opened.url)
        c.closeRecord()
        runCurrent()
        assertNull(c.ui.value.opened)

        // 앱을 다시 켜도 된다.
        kill(c)
        val again = controller()
        runCurrent()
        assertTrue(again.unlockRecords("2580"))
        again.openRecord(entry.sessionId)
        runCurrent()
        assertEquals(url, again.ui.value.opened?.url)
    }

    @Test
    fun `내용이 맞지 않는 기록으로는 QR을 만들지 않는다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        val file = sessionFile(pickup.sessionId, "pickup.json")
        file.writeText(file.readText().replace("82.0", "99.0"))

        assertTrue(c.unlockRecords("2580"))
        c.openRecord(pickup.sessionId)
        runCurrent()
        assertNull(c.ui.value.opened)
        assertEquals(RentalController.READ_RECORD_FAILED, c.ui.value.error)
    }

    // 시연 값

    // 화면 개편으로 늘어난 동작

    private val electric = VehicleState(evSocPercent = 82.0, evCapacityKwh = 77.4, odometerKm = 23_410.0, telltales = emptySet()).let { state ->
        state.copy(unavailable = StateItem.entries.filterNot(state::has).associateWith { Unavailable.Unsupported })
    }

    @Test
    fun `기록을 거절하면 시각과 차량만 남고 직원 화면 목록에 보인다`() = scenario {
        val c = controller()
        runCurrent()
        c.startPickup()
        runCurrent()
        c.declineNotice()
        runCurrent()
        assertEquals(RentalState.IDLE, c.ui.value.state)
        assertEquals(RentalController.DECLINED_CAPTION, c.ui.value.caption)
        assertTrue(c.ui.value.declines.isEmpty(), "직원 PIN으로 목록을 열 때 읽는다")

        assertTrue(c.unlockRecords("2580"))
        runCurrent()
        assertEquals(listOf(DeclinedNotice(clock, "V-0007")), c.ui.value.declines)
        assertEquals(emptyList(), c.ui.value.records, "대여가 아니라 기록 목록에 대여로 세지 않는다")

        val line = File(folder, "declined.jsonl").readText().trim()
        assertEquals("""{"at":"$clock","vehicle":"V-0007"}""", line)

        // 앱을 다시 켜도 남아 있고, 최근 것이 먼저다.
        clock = clock.plusSeconds(60)
        kill(c)
        val again = controller()
        runCurrent()
        again.startPickup()
        runCurrent()
        again.declineNotice()
        runCurrent()
        assertTrue(again.unlockRecords("2580"))
        runCurrent()
        assertEquals(listOf(clock, clock.minusSeconds(60)), again.ui.value.declines.map { it.at })
    }

    @Test
    fun `거절 기록의 잘린 줄은 건너뛴다`() = scenario {
        store.saveDecline(DeclinedNotice(clock, "V-0007"))
        File(folder, "declined.jsonl").appendText("{\"at\":\"2026")
        store.saveDecline(DeclinedNotice(clock.plusSeconds(1), "V-0007"))
        assertEquals(listOf(clock.plusSeconds(1), clock), store.declines().map { it.at })
    }

    @Test
    fun `첫 판정 전에는 눌러도 반납 기록을 남기지 않는다`() = scenario {
        val c = controller()
        pickUp(c)
        // 같은 자리 버튼을 연달아 누른 경우다. 아직 차를 읽지 않았다.
        c.startReturnCheck()
        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURN_CHECK, c.ui.value.state)
        assertTrue(c.ui.value.checkReady)
        assertNull(c.ui.value.report)

        c.confirmReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
    }

    @Test
    fun `충전하면 반납 체크의 게이지가 따라오고 찼으면 금액이 사라진다`() = scenario {
        val vehicle = TestVehicle(electric)
        val voice = FakeVoice()
        val policy = RentalPolicy(batteryCapacityKwh = 77.4, electricityPricePerKwh = 350)
        val c = controller(vehicle = vehicle, voice = voice, settings = AppSettings(reportBaseUrl = "https://example.com/chagok", policy = policy))
        pickUp(c)
        vehicle.fake.update { copy(evSocPercent = 61.0) }
        c.startReturnCheck()
        runCurrent()
        val low = assertNotNull(c.ui.value.energy)
        assertEquals("16.3kWh 충전하면 인수 때와 같습니다", low.refillText)
        assertEquals("참고 정산(업체 기준) 약 5,700원 · 청구 확정은 업체", low.settlementText)
        val said = voice.said.size

        // 잔량은 주기 확인으로도 따라온다.
        vehicle.fake.update { copy(evSocPercent = 70.0) }
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(3_300, c.ui.value.energy?.won)

        vehicle.fake.update { copy(evSocPercent = 82.0) }
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(true, c.ui.value.energy?.reached)
        assertNull(c.ui.value.energy?.settlementText)
        assertEquals(said, voice.said.size, "잔량 변화는 말하지 않는다")

        // 나갔다 오면 지난 값을 보여 주지 않는다.
        c.cancelReturnCheck()
        runCurrent()
        assertNull(c.ui.value.energy)
    }

    @Test
    fun `직원 반납 기록은 반납 체크 없이 리포트에 직원 기록으로 남는다`() = scenario {
        val c = controller()
        pickUp(c)
        c.recordStaffReturn()
        runCurrent()
        val ui = c.ui.value
        assertEquals(RentalState.RETURNED, ui.state, ui.error)
        assertEquals(true, ui.report?.byStaff)
        assertNull(ui.check.firstOrNull())

        // 앱을 다시 켜도 직원 기록이다. 저장된 값으로 되살린 무결성 값이 그대로다.
        kill(c)
        val again = controller()
        runCurrent()
        assertEquals(ui.report?.hash, again.ui.value.report?.hash)
        assertEquals(true, again.ui.value.report?.byStaff)
    }

    @Test
    fun `직원 반납 기록은 반납 체크 중에도 되고 믿을 수 없는 기록이나 주행 중에는 받지 않는다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        c.startReturnCheck()
        runCurrent()
        c.recordStaffReturn()
        runCurrent()
        assertEquals(RentalState.RETURNED, c.ui.value.state)
        assertEquals(pickup.recordNo, c.ui.value.report?.recordNo)
        c.close()
        runCurrent()

        val parked = MutableStateFlow(true)
        val moving = controller(parked = parked)
        pickUp(moving)
        parked.value = false
        runCurrent()
        moving.recordStaffReturn()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, moving.ui.value.state)
    }

    @Test
    fun `손님 반납은 직원 기록이 아니다`() = scenario {
        val c = controller()
        pickUp(c)
        finishReturn(c)
        assertEquals(false, c.ui.value.report?.byStaff)
    }

    @Test
    fun `직원 PIN을 다섯 번 틀리면 30초 동안 맞는 값도 받지 않는다`() = scenario {
        val c = controller(settings = AppSettings(reportBaseUrl = "https://example.com/chagok", staffPin = "1357"))
        runCurrent()
        repeat(4) { assertFalse(c.checkStaffPin("0000")) }
        assertEquals(0L, c.staffPinLockedMillis())
        assertTrue(c.checkStaffPin("1357"), "맞으면 틀린 횟수를 지운다")

        repeat(5) { assertFalse(c.checkStaffPin("0000")) }
        assertEquals(30_000L, c.staffPinLockedMillis())
        assertFalse(c.checkStaffPin("1357"), "잠긴 동안에는 맞아도 받지 않는다")

        clock = clock.plusSeconds(30)
        assertEquals(0L, c.staffPinLockedMillis())
        assertTrue(c.checkStaffPin("1357"))
    }

    @Test
    fun `직원 PIN은 설정의 값과 같을 때만 통과한다`() = scenario {
        val c = controller(settings = AppSettings(reportBaseUrl = "https://example.com/chagok", staffPin = "1357"))
        runCurrent()
        assertTrue(c.checkStaffPin("1357"))
        assertFalse(c.checkStaffPin("1358"))
        assertFalse(c.checkStaffPin(""))
    }

    @Test
    fun `차를 켰을 때의 알림을 누르면 고지로 간다`() = scenario {
        val voice = FakeVoice()
        val c = controller(voice = voice)
        // 복원이 끝나기 전에 와도 버리지 않는다.
        c.startFromNotification()
        runCurrent()
        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertEquals(Voice.notice(), voice.said.last().first)
    }

    @Test
    fun `알림을 눌러도 열린 대여와 주행 중에는 고지로 가지 않고 닫지 않은 리포트는 닫고 간다`() = scenario {
        val c = controller()
        pickUp(c)
        c.startFromNotification()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, c.ui.value.state)

        finishReturn(c)
        c.startFromNotification()
        runCurrent()
        assertEquals(RentalState.NOTICE, c.ui.value.state)
        assertNull(c.ui.value.report)
        c.declineNotice()
        runCurrent()

        val moving = controller(parked = MutableStateFlow(false))
        runCurrent()
        moving.startFromNotification()
        runCurrent()
        assertEquals(RentalState.IDLE, moving.ui.value.state)
    }

    @Test
    fun `대여 중 화면이 지금까지의 기록 건수와 공백을 안다`() = scenario {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val vehicle = TestVehicle()
        val c = controller(vehicle = vehicle)
        pickUp(c)
        runCurrent()
        assertEquals(RentalLog(0, 0), c.ui.value.log)

        vehicle.fake.update { withTelltale(tpms) }
        runCurrent()
        assertEquals(RentalLog(1, 0), c.ui.value.log)

        // 앱이 꺼졌던 구간은 공백으로 센다.
        clock = clock.plusSeconds(3_600)
        kill(c)
        val again = controller(vehicle = vehicle)
        runCurrent()
        assertEquals(RentalLog(1, 1), again.ui.value.log)
    }

    @Test
    fun `화면 문구에 금지어가 없다`() {
        // 화면 코드의 글자 전부를 본다. 작업 폴더는 모듈 폴더다.
        val folder = File("src/main/kotlin/app/chagok/ui")
        val sources = folder.listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(sources.isNotEmpty(), folder.absolutePath)
        val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        sources.forEach { source ->
            source.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }.forEach { line ->
                literal.findAll(line).forEach { match ->
                    assertEquals(emptyList(), Wording.violations(match.groupValues[1]), "${source.name}: ${match.value}")
                }
            }
        }
    }

    @Test
    fun `닫으면 시연 차량을 처음 상태로 돌린다`() = scenario {
        val demo = DemoVehicle()
        val c = controller(vehicle = demo.fake, onClosed = demo::reset)
        pickUp(c)
        demo.driveDuringRental()
        demo.driveDuringRental()
        finishReturn(c)
        assertEquals(DemoVehicle.START.odometerKm!! + 312.0, c.ui.value.report!!.returned.state.odometerKm)

        c.close()
        runCurrent()
        assertEquals(DemoVehicle.START, demo.fake.current)
    }

    // 지난 기록은 직원 PIN 뒤에 있다

    @Test
    fun `PIN 없이는 지난 기록의 목록도 QR도 볼 수 없다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        finishReturn(c)
        c.close()
        runCurrent()

        c.openRecord(pickup.sessionId)
        runCurrent()
        assertNull(c.ui.value.opened)
        assertEquals(emptyList(), c.ui.value.records)

        assertFalse(c.unlockRecords("0000"))
        runCurrent()
        assertFalse(c.ui.value.recordsUnlocked)
        assertEquals(emptyList(), c.ui.value.records)
        c.openRecord(pickup.sessionId)
        runCurrent()
        assertNull(c.ui.value.opened)
    }

    @Test
    fun `직원 화면을 떠나거나 앱이 뒤로 가면 다시 잠기고 읽어 둔 것을 비운다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        finishReturn(c)
        c.close()
        runCurrent()

        assertTrue(c.unlockRecords("2580"))
        runCurrent()
        c.openRecord(pickup.sessionId)
        runCurrent()
        assertNotNull(c.ui.value.opened)
        assertEquals(1, c.ui.value.records.size)

        c.leaveStaffPanel()
        assertFalse(c.ui.value.recordsUnlocked)
        assertNull(c.ui.value.opened)
        assertEquals(emptyList(), c.ui.value.records)
        c.openRecord(pickup.sessionId)
        runCurrent()
        assertNull(c.ui.value.opened, "다시 PIN을 받아야 한다")

        assertTrue(c.unlockRecords("2580"))
        runCurrent()
        c.setVisible(false)
        runCurrent()
        assertFalse(c.ui.value.recordsUnlocked)
        assertEquals(emptyList(), c.ui.value.records)
    }

    @Test
    fun `기록 목록의 PIN도 다섯 번 틀리면 잠긴다`() = scenario {
        val c = controller()
        runCurrent()
        repeat(5) { assertFalse(c.unlockRecords("0000")) }
        assertFalse(c.unlockRecords("2580"), "잠긴 동안에는 맞는 PIN도 받지 않는다")
        clock = clock.plusSeconds(31)
        assertTrue(c.unlockRecords("2580"))
    }

    @Test
    fun `지금 대여의 인수 기록은 PIN 없이 볼 수 있다`() = scenario {
        val c = controller()
        val pickup = pickUp(c)
        assertEquals(pickup.hash, c.ui.value.pickup?.hash)
        assertNotNull(c.ui.value.pickupUrl)
        assertFalse(c.ui.value.recordsUnlocked)
    }

    // 닫지 않은 리포트

    @Test
    fun `닫지 않은 리포트는 잠깐 뒤 다시 켜면 이어 가고 오래 지나면 대기로 시작한다`() = scenario {
        val first = controller()
        pickUp(first)
        finishReturn(first)
        val hash = first.ui.value.report!!.hash
        kill(first)

        clock = clock.plusSeconds(5 * 60)
        val soon = controller()
        runCurrent()
        assertEquals(RentalState.RETURNED, soon.ui.value.state)
        assertEquals(hash, soon.ui.value.report?.hash)
        kill(soon)

        clock = clock.plusSeconds(2 * 60)
        val later = controller()
        runCurrent()
        assertEquals(RentalState.IDLE, later.ui.value.state, "다음 고객에게 앞 고객의 리포트와 QR을 보여 주지 않는다")
        assertNull(later.ui.value.report)
        assertNull(later.ui.value.reportUrl)
        assertEquals(RentalState.IDLE, assertIs<Stored.Found<SavedSession>>(store.readSession()).value.state)
        assertTrue(later.unlockRecords("2580"))
        runCurrent()
        assertEquals(StoredSession.Status.RETURNED, later.ui.value.records.single().status, "기록은 직원 목록에 남는다")
    }

    // 보관 기간

    @Test
    fun `앱을 켤 때와 기록 목록을 열 때 보관 기간이 지난 대여를 지운다`() = scenario {
        val first = controller()
        val old = pickUp(first)
        finishReturn(first)
        first.close()
        runCurrent()
        kill(first)
        assertTrue(File(folder, old.sessionId).exists())

        clock = clock.plus(java.time.Duration.ofDays(181))
        val second = controller()
        runCurrent()
        assertFalse(File(folder, old.sessionId).exists(), "앱을 켜면 지운다")
        kill(second)

        // 켜 둔 채로 날이 지나도 목록을 열 때 지운다.
        val third = controller()
        runCurrent()
        val next = pickUp(third)
        finishReturn(third)
        third.close()
        runCurrent()
        clock = clock.plus(java.time.Duration.ofDays(181))
        assertTrue(third.unlockRecords("2580"))
        runCurrent()
        assertEquals(emptyList(), third.ui.value.records)
        assertFalse(File(folder, next.sessionId).exists())
    }

    @Test
    fun `대여 중에는 며칠이 지나도 그 대여를 지우지 않는다`() = scenario {
        val first = controller()
        val open = pickUp(first)
        kill(first)
        clock = clock.plus(java.time.Duration.ofDays(400))
        val second = controller()
        runCurrent()
        assertEquals(RentalState.IN_RENTAL, second.ui.value.state)
        assertEquals(open.hash, second.ui.value.pickup?.hash)
    }

    // 진단

    @Test
    fun `진단은 읽기만 하고 아무것도 저장하지 않는다`() = scenario {
        val c = controller()
        runCurrent()
        val before = folder.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        c.readDiagnosis()
        runCurrent()
        val diagnosis = assertNotNull(c.ui.value.diagnosis)
        assertEquals(clock, diagnosis.readAt)
        assertEquals(before, folder.walkTopDown().filter { it.isFile }.map { it.name }.toSet())
        c.leaveStaffPanel()
        assertNull(c.ui.value.diagnosis)
    }
}
