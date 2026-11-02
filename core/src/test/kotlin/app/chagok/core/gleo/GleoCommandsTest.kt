package app.chagok.core.gleo

import app.chagok.core.model.Area
import app.chagok.core.model.StateItem
import app.chagok.core.rental.CheckItem
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.Voice
import app.chagok.core.rental.Wording
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GleoCommandsTest {
    private val window = CheckItem.WindowOpen(Area.FRONT_RIGHT)
    private val trunk = CheckItem.ClosureOpen(StateItem.TRUNK)
    private val battery = CheckItem.EnergyDrop(StateItem.EV_SOC, 82, 61)

    @Test
    fun `함수는 세 개이고 확정·무효·닫기는 입구에 없다`() {
        assertEquals(setOf("RENTAL_START_RETURN_CHECK", "RENTAL_WHAT_TO_CHECK", "RENTAL_SHOW_RECORD"), GleoFunction.entries.map { it.action }.toSet())
        assertNull(GleoFunction.of("RENTAL_CONFIRM_RETURN"))
        assertNull(GleoFunction.of("RENTAL_VOID"))
        assertNull(GleoFunction.of("android.intent.action.MAIN"))
        assertNull(GleoFunction.of(null))
    }

    @Test
    fun `파라미터는 없거나 비었거나 JSON 객체면 받고 값은 쓰지 않는다`() {
        listOf(null, "", "  ", "{}", """{"confirm":true,"reason":"x"}""", "\"{}\"", "\"\"", """"{"title":"미팅"}"""").forEach {
            assertTrue(GleoCommands.validParameters(it), it.toString())
        }
    }

    @Test
    fun `읽을 수 없거나 너무 긴 파라미터는 받지 않는다`() {
        listOf("not json", "[1,2]", "1", "{", """{"a":""", "{" + "x".repeat(2000) + "}").forEach {
            assertFalse(GleoCommands.validParameters(it), it)
        }
    }

    @Test
    fun `대여 중이고 P단이면 반납 체크를 시작하고 말은 앱이 한다`() {
        val decision = GleoCommands.startReturnCheck(RentalState.IN_RENTAL, parked = true, damaged = false)
        assertTrue(decision.act)
        assertEquals(GleoStatus.SUCCESS, decision.reply.status)
        assertEquals(GleoMode.SILENT, decision.reply.mode)
    }

    @Test
    fun `P단이 아니면 시작하지 않고 이유를 답한다`() {
        val decision = GleoCommands.startReturnCheck(RentalState.IN_RENTAL, parked = false, damaged = false)
        assertFalse(decision.act)
        assertEquals(GleoStatus.FAIL, decision.reply.status)
        assertTrue(decision.reply.message.contains("park"), decision.reply.message)
    }

    @Test
    fun `대여 중이 아니면 어느 함수도 받지 않고 이유를 답한다`() {
        listOf(RentalState.IDLE, RentalState.NOTICE, RentalState.PICKUP).forEach { state ->
            val start = GleoCommands.startReturnCheck(state, parked = true, damaged = false)
            assertFalse(start.act, state.name)
            assertEquals(GleoStatus.FAIL, start.reply.status)
            assertEquals("No rental is in progress.", start.reply.message)
            assertFalse(GleoCommands.showRecord(state, parked = true, damaged = false).act, state.name)
            assertEquals("No rental is in progress.", GleoCommands.whatToCheckRefusal(state, damaged = false)?.message)
        }
        listOf(RentalState.RETURNED, RentalState.VOIDED).forEach { state ->
            assertFalse(GleoCommands.startReturnCheck(state, parked = true, damaged = false).act, state.name)
            assertEquals(GleoStatus.FAIL, GleoCommands.whatToCheckRefusal(state, damaged = false)?.status)
        }
    }

    @Test
    fun `기록을 믿을 수 없으면 직원에게 넘긴다`() {
        assertEquals(GleoStatus.FAIL, GleoCommands.startReturnCheck(RentalState.IN_RENTAL, parked = true, damaged = true).reply.status)
        assertFalse(GleoCommands.startReturnCheck(RentalState.IN_RENTAL, parked = true, damaged = true).act)
        assertFalse(GleoCommands.showRecord(RentalState.IN_RENTAL, parked = true, damaged = true).act)
        assertFalse(GleoCommands.showRecord(RentalState.RETURNED, parked = true, damaged = true).act)
        assertTrue(GleoCommands.whatToCheckRefusal(RentalState.IN_RENTAL, damaged = true)!!.message.contains("staff"))
    }

    @Test
    fun `이미 반납 체크 중이면 다시 시작하지 않는다`() {
        val again = GleoCommands.startReturnCheck(RentalState.RETURN_CHECK, parked = true, damaged = false)
        assertFalse(again.act)
        assertEquals(GleoStatus.SUCCESS, again.reply.status)
        assertFalse(GleoCommands.startReturnCheck(RentalState.RETURN, parked = true, damaged = false).act)
    }

    @Test
    fun `대여 중이면 인수 기록을 보여 주고 반납 뒤에는 리포트가 이미 화면에 있다`() {
        val inRental = GleoCommands.showRecord(RentalState.IN_RENTAL, parked = true, damaged = false)
        assertTrue(inRental.act)
        assertEquals(GleoStatus.SUCCESS, inRental.reply.status)

        val returned = GleoCommands.showRecord(RentalState.RETURNED, parked = true, damaged = false)
        assertFalse(returned.act)
        assertEquals(GleoStatus.SUCCESS, returned.reply.status)

        assertFalse(GleoCommands.showRecord(RentalState.RETURN_CHECK, parked = true, damaged = false).act)
        assertFalse(GleoCommands.showRecord(RentalState.IN_RENTAL, parked = false, damaged = false).act, "주행 중에는 보여 주지 않는다")
    }

    @Test
    fun `뭘 확인해야 하는지 묻는 답은 고칠 것과 배터리 차이를 담고 반납 기록을 묻지 않는다`() {
        val reply = GleoCommands.whatToCheck(listOf(window, trunk, battery), speakByApp = true)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals(GleoMode.SILENT, reply.mode)
        assertEquals(
            "Before the return, 2 item(s) need checking: 조수석 창문, 트렁크. 배터리 was 82 percent at pickup and is 61 percent now.",
            reply.message,
        )
        val data = reply.data!!
        assertEquals(
            "반납 전에 2가지를 확인해 주세요. 조수석 창문이 열려 있고, 트렁크가 열려 있습니다. 배터리는 인수 때 82퍼센트, 지금 61퍼센트입니다.",
            data[0].jsonObject["summary"]!!.jsonPrimitive.content,
        )
        assertEquals("조수석 창문", data[1].jsonObject["item"]!!.jsonPrimitive.content)
        assertEquals("82", data[3].jsonObject["pickupPercent"]!!.jsonPrimitive.content)

        val nothing = GleoCommands.whatToCheck(emptyList(), speakByApp = false)
        assertEquals(GleoMode.VOICE, nothing.mode, "앱이 말할 수 없으면 Gleo가 말한다")
        assertEquals("Nothing needs to be fixed before the return.", nothing.message)
        assertEquals("고칠 것이 없습니다.", GleoCommands.speech(emptyList()))
    }

    @Test
    fun `응답은 문서의 형식을 따른다`() {
        val json = Json.parseToJsonElement(GleoCommands.whatToCheck(listOf(window), speakByApp = true).toJson()).jsonObject
        assertEquals("RENTAL_WHAT_TO_CHECK", json["functionName"]!!.jsonPrimitive.content)
        assertEquals("success", json["status"]!!.jsonPrimitive.content)
        assertEquals("silent", json["mode"]!!.jsonPrimitive.content)
        // data는 JSON 객체 배열을 담은 문자열이다.
        val data = Json.parseToJsonElement(json["data"]!!.jsonPrimitive.content).jsonArray
        assertEquals(2, data.size)

        val fail = Json.parseToJsonElement(GleoCommands.invalidParameters(GleoFunction.SHOW_RECORD).toJson()).jsonObject
        assertEquals("fail", fail["status"]!!.jsonPrimitive.content)
        assertNull(fail["data"])
    }

    @Test
    fun `답에 금지어가 없고 음성은 15초 안에 끝난다`() {
        val all = StateItem.entries.filter { it == StateItem.WINDOWS || it == StateItem.DOORS }.map { CheckItem.Unreadable(it) } +
            listOf(window, trunk, CheckItem.SeatDetected(Area.REAR_LEFT), battery, CheckItem.WindowOpen(Area.REAR_LEFT), CheckItem.WindowOpen(Area.REAR_RIGHT))
        val replies = listOf(
            GleoCommands.whatToCheck(all, speakByApp = true),
            GleoCommands.whatToCheckTimedOut(),
            GleoCommands.whatToCheckBusy(),
            GleoCommands.invalidParameters(GleoFunction.START_RETURN_CHECK),
        ) + RentalState.entries.flatMap { state ->
            listOf(true, false).flatMap { parked ->
                listOf(true, false).flatMap { damaged ->
                    listOf(GleoCommands.startReturnCheck(state, parked, damaged).reply, GleoCommands.showRecord(state, parked, damaged).reply)
                }
            }
        }
        replies.forEach {
            assertEquals(emptyList(), Wording.violations(it.toJson()), it.message)
            // 책임을 판단하는 말을 영어로도 쓰지 않는다.
            listOf("fault", "liable", "liability", "penalty", "damage", "accident", "charge").forEach { word ->
                assertFalse(it.message.contains(word, ignoreCase = true), it.message)
            }
        }
        val speech = GleoCommands.speech(all)
        assertTrue(Voice.estimatedSeconds(speech) <= Voice.MAX_SECONDS, speech)
    }
}
