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
    private val fixableItems = listOf(
        StateItem.WINDOWS, StateItem.DOORS, StateItem.HEADLIGHTS, StateItem.TAIL_LAMP, StateItem.CABIN_LIGHT,
    )

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
    fun `고칠 것이 없으면 리포트에 남는 것을 말하고 반납 기록을 남길지 묻는다`() {
        assertEquals(
            "고칠 것이 없습니다. 연료는 인수 때 82퍼센트, 지금 61퍼센트입니다. 이 차이는 리포트에 남습니다. 반납 기록을 남길까요?",
            Voice.returnCheck(listOf(CheckItem.EnergyDrop(StateItem.FUEL, 82, 61))),
        )
        assertEquals("고칠 것이 없습니다. 반납 기록을 남길까요?", Voice.returnCheck(emptyList()))
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
    fun `다 말해도 15초 안이면 외 N가지로 밀지 않는다`() {
        val items = listOf(CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.EnergyDrop(StateItem.FUEL, 82, 61), CheckItem.NewTelltales(setOf(engine)))
        val speech = Voice.returnCheck(items)

        assertEquals(
            "반납 전에 1가지를 확인해 주세요. 조수석 창문이 열려 있습니다. 연료는 인수 때 82퍼센트, 지금 61퍼센트입니다. 새로 켜진 경고등 1개를 기록에 남깁니다.",
            speech,
        )
        // 예전 계산은 여유분 2.9초를 빼서 12.1초가 넘으면 마지막 문장을 밀어냈다.
        assertTrue(Voice.estimatedSeconds(speech) in 12.1..Voice.MAX_SECONDS, speech)
    }

    @Test
    fun `15초를 넘으면 외 N가지 몫을 남기고 끊는다`() {
        val items = listOf(
            CheckItem.WindowOpen(Area.REAR_LEFT), CheckItem.WindowOpen(Area.REAR_RIGHT),
            CheckItem.SeatDetected(Area.REAR_RIGHT),
            CheckItem.EnergyDrop(StateItem.FUEL, 82, 61), CheckItem.NewTelltales(setOf(engine)),
        )
        val speech = Voice.returnCheck(items)

        assertTrue(Voice.estimatedSeconds(speech) <= Voice.MAX_SECONDS, speech)
        assertTrue(speech.contains("외 ") && speech.endsWith("가지는 화면을 확인해 주세요."), speech)
    }

    @Test
    fun `읽지 못한 것이 있으면 고칠 것이 없다고 하지 않는다`() {
        val unreadable = CheckItem.Unreadable(StateItem.WINDOWS)
        val fuel = CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)

        assertEquals(
            "반납 전에 1가지를 확인해 주세요. 창문을 읽지 못했습니다. 직접 확인해 주세요. 연료는 인수 때 82퍼센트, 지금 61퍼센트입니다.",
            Voice.returnCheck(listOf(unreadable, fuel)),
        )
        assertEquals("미등이 꺼졌습니다. 창문만 남았습니다.", Voice.resolved(CheckItem.LightOn(StateItem.TAIL_LAMP), listOf(unreadable, fuel)))
        assertEquals("창문을 읽지 못했습니다. 직접 확인해 주세요.", Voice.appeared(unreadable, listOf(unreadable, fuel)))
    }

    @Test
    fun `남은 것이 둘이면 이름을 말하고 셋 이상이면 개수를 말한다`() {
        val door = CheckItem.DoorOpen(Area.FRONT_LEFT)
        val window = CheckItem.WindowOpen(Area.FRONT_RIGHT)
        val tail = CheckItem.LightOn(StateItem.TAIL_LAMP)
        val cabin = CheckItem.LightOn(StateItem.CABIN_LIGHT)
        val fuel = CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)

        assertEquals("운전석 문이 닫혔습니다. 조수석 창문과 미등이 남았습니다.", Voice.resolved(door, listOf(window, tail, fuel)))
        assertEquals("미등이 꺼졌습니다. 운전석 문과 조수석 창문이 남았습니다.", Voice.resolved(tail, listOf(door, window)))
        assertEquals("운전석 문이 닫혔습니다. 3가지 남았습니다.", Voice.resolved(door, listOf(window, tail, cabin, fuel)))
    }

    @Test
    fun `고친 항목을 알리고 남은 것을 말한다`() {
        val fuel = CheckItem.EnergyDrop(StateItem.FUEL, 82, 61)
        assertEquals(
            "조수석 창문이 닫혔습니다. 실내등만 남았습니다.",
            Voice.resolved(CheckItem.WindowOpen(Area.FRONT_RIGHT), listOf(CheckItem.LightOn(StateItem.CABIN_LIGHT), fuel)),
        )
        assertEquals("실내등이 꺼졌습니다. 고칠 것은 모두 끝났습니다. 연료 차이는 리포트에 남습니다. 반납 기록을 남길까요?", Voice.resolved(CheckItem.LightOn(StateItem.CABIN_LIGHT), listOf(fuel)))
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
    fun `트렁크와 충전구도 열림과 닫힘을 말한다`() {
        val trunk = CheckItem.ClosureOpen(StateItem.TRUNK)
        val port = CheckItem.ClosureOpen(StateItem.CHARGE_PORT)
        assertEquals(
            "반납 전에 2가지를 확인해 주세요. 트렁크가 열려 있고, 충전구가 열려 있습니다.",
            Voice.returnCheck(listOf(trunk, port)),
        )
        assertEquals("트렁크가 닫혔습니다. 충전구만 남았습니다.", Voice.resolved(trunk, listOf(port)))
        assertEquals("충전구가 닫혔습니다. 고칠 것은 모두 끝났습니다. 반납 기록을 남길까요?", Voice.resolved(port, emptyList()))
        assertEquals("트렁크가 열렸습니다.", Voice.appeared(trunk, listOf(trunk)))
    }

    @Test
    fun `전기차 시연의 반납 안내도 15초 안에 끝난다`() {
        val items = listOf(
            CheckItem.WindowOpen(Area.FRONT_RIGHT), CheckItem.ClosureOpen(StateItem.TRUNK), CheckItem.LightOn(StateItem.CABIN_LIGHT),
            CheckItem.SeatDetected(Area.REAR_LEFT), CheckItem.EnergyDrop(StateItem.EV_SOC, 82, 61), CheckItem.NewTelltales(setOf(Telltale(Telltale.TPMS, "타이어 공기압"))),
        )
        val text = Voice.returnCheck(items)
        assertTrue(Voice.estimatedSeconds(text) <= Voice.MAX_SECONDS, "$text (${Voice.estimatedSeconds(text)}초)")
        assertTrue(text.startsWith("반납 전에 4가지를 확인해 주세요. 조수석 창문이 열려 있고, 트렁크가 열려 있고, 실내등이 켜져 있습니다."), text)
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
            Voice.returnCheck(listOf(CheckItem.TelltalesUnverified(setOf(engine)))),
            Voice.returnCheck(fixableItems.map { CheckItem.Unreadable(it) }),
            Voice.resolved(CheckItem.Unreadable(StateItem.WINDOWS), emptyList())!!,
            Voice.appeared(CheckItem.Unreadable(StateItem.WINDOWS), emptyList())!!,
            Voice.resolved(CheckItem.SeatDetected(Area.REAR_RIGHT), emptyList())!!,
            Voice.appeared(CheckItem.WindowOpen(Area.REAR_RIGHT), List(4) { CheckItem.WindowOpen(Area.REAR_RIGHT) })!!,
            Voice.returnDone(),
            Voice.lowBattery(20),
            Voice.arrived(),
            Voice.leaving(),
            Voice.resolved(
                CheckItem.DoorOpen(Area.REAR_LEFT),
                listOf(CheckItem.EnergyDrop(StateItem.EV_SOC, 82, 61), CheckItem.NewTelltales(setOf(engine))),
            )!!,
        )
        samples.forEach {
            assertEquals(emptyList(), Wording.violations(it), it)
            assertTrue(Voice.estimatedSeconds(it) <= Voice.MAX_SECONDS, it)
        }
    }

    @Test
    fun `모두 끝났다는 말은 고칠 것만 말하고 리포트의 확인 필요와 어긋나지 않는다`() {
        val battery = CheckItem.EnergyDrop(StateItem.EV_SOC, 82, 61)
        val lamp = CheckItem.NewTelltales(setOf(engine))
        val closed = CheckItem.WindowOpen(Area.FRONT_RIGHT)

        assertEquals("배터리 차이는 리포트에 남습니다.", Voice.reportNote(listOf(battery)))
        assertEquals("경고등은 리포트에 남습니다.", Voice.reportNote(listOf(lamp)))
        assertEquals("경고등은 리포트에 남습니다.", Voice.reportNote(listOf(CheckItem.TelltalesUnverified(setOf(engine)))))
        assertEquals("배터리 차이와 경고등은 리포트에 남습니다.", Voice.reportNote(listOf(battery, lamp)))
        assertEquals(null, Voice.reportNote(listOf(closed)))

        val spoken = Voice.resolved(closed, listOf(battery, lamp))!!
        assertEquals("조수석 창문이 닫혔습니다. 고칠 것은 모두 끝났습니다. 배터리 차이와 경고등은 리포트에 남습니다. 반납 기록을 남길까요?", spoken)
        assertTrue(spoken.endsWith(Voice.RECORD_QUESTION), "이 문장 뒤에 답을 듣는다")
        listOf(spoken, Voice.ALL_FIXED, Voice.NOTHING_TO_FIX, Voice.returnCheck(listOf(battery, lamp))).forEach {
            assertTrue("모두 확인" !in it && "확인할 것이 없" !in it, it)
        }
    }

    @Test
    fun `내리려 할 때의 안내는 반납 기록이 없다는 사실과 할 일을 말한다`() {
        assertEquals("잠깐만요. 아직 반납 기록을 남기지 않았습니다. 내리기 전에 반납 체크를 해 주세요.", Voice.leaving())
    }
}
