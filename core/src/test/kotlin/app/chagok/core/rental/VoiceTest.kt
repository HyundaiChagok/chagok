package app.chagok.core.rental

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {
    private val engine = Telltale("ENGINE", "엔진 점검")

    @Test
    fun `인수 안내는 잔량과 경고등을 말한다`() {
        assertEquals(
            "인수 기록을 남겼습니다. 연료 82퍼센트, 켜진 경고등은 없습니다. 화면의 QR로 같은 기록을 받으세요.",
            Voice.pickupDone(VehicleState(fuelPercent = 82.0, telltales = emptySet())),
        )
        assertEquals(
            "인수 기록을 남겼습니다. 배터리 64퍼센트, 켜져 있는 경고등 1개를 함께 남겼습니다. 화면의 QR로 같은 기록을 받으세요.",
            Voice.pickupDone(VehicleState(evSocPercent = 64.0, telltales = setOf(engine))),
        )
        assertEquals("인수 기록을 남겼습니다. 연료 82퍼센트입니다. 화면의 QR로 같은 기록을 받으세요.", Voice.pickupDone(VehicleState(fuelPercent = 82.0)))
        assertEquals("인수 기록을 남겼습니다. 화면의 QR로 같은 기록을 받으세요.", Voice.pickupDone(VehicleState()))
    }

    @Test
    fun `반납 체크는 고칠 것부터 말한다`() {
        val items = listOf(CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.LightOn(StateItem.CABIN_LIGHT))
        assertEquals("반납 전에 2가지를 확인해 주세요. 조수석 창문이 열려 있고, 실내등이 켜져 있습니다.", Voice.returnCheck(items))
    }

    @Test
    fun `확인할 것이 없으면 반납 기록을 남길지 묻는다`() {
        assertEquals(
            "확인할 것이 없습니다. 연료는 인수 때 82퍼센트, 지금 61퍼센트입니다. 반납 기록을 남길까요?",
            Voice.returnCheck(listOf(CheckItem.EnergyDrop(StateItem.FUEL, 82, 61))),
        )
        assertEquals("확인할 것이 없습니다. 반납 기록을 남길까요?", Voice.returnCheck(emptyList()))
    }

    @Test
    fun `길면 15초 안에서 끊고 나머지는 화면으로 넘긴다`() {
        val items = listOf(
            CheckItem.WindowOpen(Area.FRONT_LEFT), CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.WindowOpen(Area.REAR_LEFT),
            CheckItem.DoorOpen(Area.REAR_RIGHT), CheckItem.LightOn(StateItem.TAIL_LAMP),
            CheckItem.SeatDetected(Area.FRONT_RIGHT), CheckItem.EnergyDrop(StateItem.FUEL, 82, 61), CheckItem.NewTelltales(setOf(engine)),
        )
        val speech = Voice.returnCheck(items)

        assertTrue(Voice.estimatedSeconds(speech) <= Voice.MAX_SECONDS, speech)
        assertTrue(
            speech.startsWith("반납 전에 6가지를 확인해 주세요. 운전석 창문이 열려 있고, 조수석 창문이 열려 있고, 뒷좌석 왼쪽 창문이 열려 있습니다."),
            speech,
        )
        assertTrue(speech.endsWith("가지는 화면을 확인해 주세요."), speech)
    }

    @Test
    fun `고친 항목을 알리고 남은 것을 말한다`() {
        val fuel = CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)
        assertEquals(
            "조수석 창문이 닫혔습니다. 실내등만 남았습니다.",
            Voice.resolved(CheckItem.WindowOpen(Area.FRONT_RIGHT), listOf(CheckItem.LightOn(StateItem.CABIN_LIGHT), fuel)),
        )
        assertEquals("실내등이 꺼졌습니다. 모두 확인됐습니다. 반납 기록을 남길까요?", Voice.resolved(CheckItem.LightOn(StateItem.CABIN_LIGHT), listOf(fuel)))
        assertEquals(null, Voice.resolved(fuel, emptyList()))
    }

    @Test
    fun `반납 체크 도중 새로 열리거나 켜진 것을 알린다`() {
        val door = CheckItem.DoorOpen(Area.FRONT_LEFT)
        val window = CheckItem.WindowOpen(Area.FRONT_RIGHT)
        val tail = CheckItem.LightOn(StateItem.TAIL_LAMP)
        val fuel = CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)
        assertEquals("운전석 문이 열렸습니다.", Voice.appeared(door, listOf(door, fuel)))
        assertEquals("운전석 문이 열렸습니다. 확인할 것이 2가지입니다.", Voice.appeared(door, listOf(window, door, fuel)))
        assertEquals("미등이 켜졌습니다.", Voice.appeared(tail, listOf(tail)))
        assertEquals("뒷좌석 오른쪽에 무언가 감지됩니다.", Voice.appeared(CheckItem.SeatDetected(Area.REAR_RIGHT), emptyList()))
        // 잔량·경고등은 말하지 않는다.
        assertEquals(null, Voice.appeared(fuel, listOf(fuel)))
        assertEquals(null, Voice.appeared(CheckItem.NewTelltales(setOf(engine)), emptyList()))
    }

    @Test
    fun `받침에 따라 조사를 고른다`() {
        assertEquals("창문이", "창문".josa("이", "가"))
        assertEquals("배터리가", "배터리".josa("이", "가"))
        assertEquals("연료는", "연료".josa("은", "는"))
    }

    @Test
    fun `모든 안내 문구는 금지어가 없고 15초 안에 끝난다`() {
        val samples = listOf(
            Voice.notice(),
            Voice.pickupDone(VehicleState(fuelPercent = 82.0, telltales = setOf(engine))),
            Voice.returnCheck(listOf(CheckItem.DoorOpen(Area.FRONT_LEFT), CheckItem.SeatDetected(Area.REAR_RIGHT))),
            Voice.returnCheck(listOf(CheckItem.NewTelltales(setOf(engine)))),
            Voice.resolved(CheckItem.SeatDetected(Area.REAR_RIGHT), emptyList())!!,
            Voice.appeared(CheckItem.WindowOpen(Area.REAR_RIGHT), List(4) { CheckItem.WindowOpen(Area.REAR_RIGHT) })!!,
            Voice.returnDone(),
            Voice.lowBattery(20),
            Voice.arrived(),
        )
        samples.forEach {
            assertEquals(emptyList(), Wording.violations(it), it)
            assertTrue(Voice.estimatedSeconds(it) <= Voice.MAX_SECONDS, it)
        }
    }
}
