package app.chagok.core.rental

import app.chagok.core.model.Area
import app.chagok.core.model.FluidLevel
import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.StateItem
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineTest {
    private val tpms = Telltale("TPMS", "타이어 공기압")
    private val engine = Telltale("ENGINE", "엔진 점검")
    private val t0 = Instant.parse("2026-11-04T05:05:00Z")

    private fun at(seconds: Long) = t0.plusSeconds(seconds)

    private fun logged(seconds: Long, event: VehicleEvent) = LoggedEvent(at(seconds), null, event)

    @Test
    fun `인수 기록과 달라진 것만 문구가 되고 같은 값이 다시 오면 남지 않는다`() {
        val fold = TimelineFold(
            VehicleState(telltales = setOf(engine), engineOil = FluidLevel.NORMAL, airbagsDeployed = mapOf(Area.FRONT_LEFT to false)),
        )
        // 리스너가 구독 직후 현재 값을 한 번 주는 경우
        assertEquals(emptyList(), fold.apply(VehicleEvent.TelltalesChanged(setOf(engine))))
        assertEquals(emptyList(), fold.apply(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.NORMAL)))
        assertEquals(emptyList(), fold.apply(VehicleEvent.AirbagChanged(Area.FRONT_LEFT, false)))

        assertEquals(
            listOf("경고등 켜짐: 타이어 공기압", "경고등 꺼짐: 엔진 점검"),
            fold.apply(VehicleEvent.TelltalesChanged(setOf(tpms))),
        )
        assertEquals(emptyList(), fold.apply(VehicleEvent.TelltalesChanged(setOf(tpms))))
        assertEquals(listOf("엔진오일 정상 → 부족"), fold.apply(VehicleEvent.FluidChanged(StateItem.ENGINE_OIL, FluidLevel.LOW)))
        assertEquals(listOf("에어백 전개 기록: 운전석"), fold.apply(VehicleEvent.AirbagChanged(Area.FRONT_LEFT, true)))
    }

    @Test
    fun `인수 때 읽지 못한 값은 바뀌었다고 말하지 않는다`() {
        val fold = TimelineFold(VehicleState())
        assertEquals(listOf("경고등 켜져 있음: 타이어 공기압"), fold.apply(VehicleEvent.TelltalesChanged(setOf(tpms))))
        assertEquals(emptyList(), fold.apply(VehicleEvent.FluidChanged(StateItem.BRAKE_FLUID, FluidLevel.NORMAL)))
        assertEquals(listOf("워셔액 부족"), fold.apply(VehicleEvent.FluidChanged(StateItem.WASHER_FLUID, FluidLevel.LOW)))
        assertEquals(emptyList(), fold.apply(VehicleEvent.AirbagChanged(Area.FRONT_RIGHT, false)))
    }

    @Test
    fun `대여 중 변화는 시간순이고 시각은 초까지만 남는다`() {
        val entries = Rental.timeline(
            VehicleState(telltales = emptySet()),
            listOf(
                LoggedEvent(at(60).plusMillis(250), null, VehicleEvent.TelltalesChanged(setOf(tpms))),
                logged(0, VehicleEvent.TelltalesChanged(setOf(tpms, engine))),
                logged(30, VehicleEvent.Note(VehicleEvent.SOURCE_ERROR)),
            ),
        )
        assertEquals(
            listOf(
                TimelineEntry(at(0), "경고등 켜짐: 엔진 점검"),
                TimelineEntry(at(0), "경고등 켜짐: 타이어 공기압"),
                TimelineEntry(at(60), "경고등 꺼짐: 엔진 점검"),
            ),
            entries,
        )
    }

    @Test
    fun `구독이 끊긴 구간을 공백으로 잡고 끊긴 채 끝나면 끝 시각까지로 본다`() {
        val events = listOf(
            logged(10, VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, "끊김")),
            logged(20, VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, "또 끊김")),
            logged(30, VehicleEvent.Note(VehicleEvent.SOURCE_RESUMED)),
            logged(100, VehicleEvent.Note(VehicleEvent.SOURCE_ERROR, "끊김")),
        )
        assertEquals(
            listOf(RecordGap(at(10), at(30)), RecordGap(at(100), at(200))),
            Rental.gaps(events, emptyList(), at(200)),
        )
    }

    @Test
    fun `겹치거나 맞닿은 공백은 합치고 길이가 없는 공백은 뺀다`() {
        val gaps = listOf(
            RecordGap(at(100), at(50)),
            RecordGap(at(30), at(60)),
            RecordGap(at(0), at(40)),
            RecordGap(at(60), at(70)),
            RecordGap(at(90), at(95)),
            RecordGap(at(120), at(120).plusMillis(400)),
        )
        assertEquals(listOf(RecordGap(at(0), at(70)), RecordGap(at(90), at(95))), Rental.gaps(emptyList(), gaps, at(200)))
    }

    @Test
    fun `타임라인 한 줄을 저장했다가 그대로 읽는다`() {
        val lines = listOf(
            TimelineLine.Event(logged(0, VehicleEvent.TelltalesChanged(setOf(tpms, engine)))),
            TimelineLine.Event(logged(1, VehicleEvent.FluidChanged(StateItem.WASHER_FLUID, FluidLevel.LOW))),
            TimelineLine.Event(logged(2, VehicleEvent.AirbagChanged(Area.FRONT_RIGHT, true))),
            TimelineLine.Event(logged(3, VehicleEvent.Note(VehicleEvent.SOURCE_RESUMED))),
            TimelineLine.Gap(RecordGap(at(4), at(5))),
        )
        lines.forEach { assertEquals(it, TimelineJson.decode(TimelineJson.encode(it)!!)) }
    }

    @Test
    fun `문 열림과 위치는 형식에 자리가 없고 잘린 줄은 읽지 않는다`() {
        assertNull(TimelineJson.encode(TimelineLine.Event(logged(0, VehicleEvent.DoorChanged(Area.FRONT_LEFT, true)))))
        assertNull(TimelineJson.encode(TimelineLine.Event(logged(0, VehicleEvent.SeatOccupancyChanged(Area.FRONT_LEFT, true)))))

        val line = TimelineJson.encode(TimelineLine.Event(logged(0, VehicleEvent.TelltalesChanged(setOf(tpms)))))!!
        assertNull(TimelineJson.decode(line.dropLast(5)))
        assertNull(TimelineJson.decode(""))
        assertNull(TimelineJson.decode("""{"at":"2026-11-04T05:05:00Z","type":"door"}"""))
    }
}
