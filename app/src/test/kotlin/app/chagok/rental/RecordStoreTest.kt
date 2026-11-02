package app.chagok.rental

import app.chagok.core.model.LoggedEvent
import app.chagok.core.model.Telltale
import app.chagok.core.model.VehicleEvent
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.RecordGap
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalReport
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.TimelineLine
import app.chagok.demo.DemoVehicle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordStoreTest {
    private val folder: File = Files.createTempDirectory("chagok-store").toFile()
    private val store = RecordStore(folder)
    private val t0 = Instant.parse("2026-11-03T00:12:00Z")

    @AfterTest
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun record(kind: SnapshotKind, at: Instant = t0, sessionId: String = "s-1") = SnapshotRecord(
        sessionId = sessionId, recordNo = "0007-1103-A1", kind = kind, vehicleId = "V-0007", capturedAt = at,
        state = DemoVehicle.START.forRecord(Rental.RECORDED), items = Rental.RECORDED, appVersion = "0.1.0", rulesVersion = Rental.RULES_VERSION,
    )

    private fun leftovers() = folder.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()

    @Test
    fun `기록을 온전히 쓰고 임시 파일을 남기지 않으며 그대로 읽는다`() {
        val pickup = record(SnapshotKind.PICKUP)
        store.save(pickup)

        assertEquals(emptyList(), leftovers())
        assertEquals(pickup, assertIs<Stored.Found<SnapshotRecord>>(store.readPickup("s-1")).value)
        assertEquals(Stored.Missing, store.readReturn("s-1"))
    }

    @Test
    fun `같은 기록을 다시 저장해도 되지만 다른 내용으로 덮어쓰지 않는다`() {
        val pickup = record(SnapshotKind.PICKUP)
        store.save(pickup)
        val file = File(folder, "s-1/pickup.json")
        val saved = file.readText()

        store.save(pickup)
        assertFailsWith<IOException> { store.save(record(SnapshotKind.PICKUP, t0.plusSeconds(1))) }

        assertEquals(saved, file.readText())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `무결성 값이 없거나 잘리거나 다른 대여의 기록이면 손상으로 본다`() {
        store.save(record(SnapshotKind.PICKUP))
        val file = File(folder, "s-1/pickup.json")
        val saved = file.readText()

        file.writeText(saved.replace(Regex(""","hash":"[^"]+"""), ""))
        assertIs<Stored.Damaged>(store.readPickup("s-1"))

        file.writeText(saved.dropLast(10))
        assertIs<Stored.Damaged>(store.readPickup("s-1"))

        file.writeText(saved)
        File(folder, "s-2").mkdirs()
        file.copyTo(File(folder, "s-2/pickup.json"))
        assertIs<Stored.Damaged>(store.readPickup("s-2"))
    }

    @Test
    fun `쓰다 꺼져 남은 임시 파일은 앱을 켤 때 지우고 진짜 파일은 그대로 둔다`() {
        store.save(record(SnapshotKind.PICKUP))
        File(folder, "s-1/return.json.tmp").writeText("""{"schema":"snap""")
        File(folder, "session.json.tmp").writeText("")
        assertEquals(2, leftovers().size)

        store.removeUnfinishedWrites()

        assertEquals(emptyList(), leftovers())
        assertIs<Stored.Found<SnapshotRecord>>(store.readPickup("s-1"))
        assertEquals(Stored.Missing, store.readReturn("s-1"))
    }

    @Test
    fun `세션 상태를 원자적으로 덮어쓰고 그대로 읽는다`() {
        assertEquals(Stored.Missing, store.readSession())

        store.saveSession(SavedSession(RentalState.NOTICE, null, null, t0))
        assertEquals(SavedSession(RentalState.NOTICE, null, null, t0), assertIs<Stored.Found<SavedSession>>(store.readSession()).value)

        val open = SavedSession(RentalState.IN_RENTAL, "s-1", "0007-1103-A1", t0.plusSeconds(5))
        store.saveSession(open)
        assertEquals(open, assertIs<Stored.Found<SavedSession>>(store.readSession()).value)
        assertEquals(emptyList(), leftovers())

        File(folder, "session.json").writeText("{")
        assertIs<Stored.Damaged>(store.readSession())
    }

    @Test
    fun `쓸 수 없으면 예외를 던지고 잘린 파일을 남기지 않는다`() {
        val blocked = RecordStore(File(folder, "blocked").apply { writeText("") })
        assertFailsWith<IOException> { blocked.save(record(SnapshotKind.PICKUP)) }
        assertFailsWith<IOException> { blocked.saveSession(SavedSession(RentalState.IDLE, null, null, t0)) }
        assertFailsWith<IOException> { blocked.nextSequence(LocalDate.of(2026, 11, 3)) }
        assertEquals(Stored.Missing, blocked.readSession())

        // 임시 파일 자리가 막혀 쓰기가 실패해도 이전 내용은 그대로다.
        store.saveSession(SavedSession(RentalState.IDLE, null, null, t0))
        File(folder, "session.json.tmp").mkdirs()
        assertFailsWith<IOException> { store.saveSession(SavedSession(RentalState.IN_RENTAL, "s-1", "x", t0)) }
        assertEquals(RentalState.IDLE, assertIs<Stored.Found<SavedSession>>(store.readSession()).value.state)
    }

    @Test
    fun `기록 번호 순번은 날마다 세고 다시 켜도 이어 간다`() {
        val day = LocalDate.of(2026, 11, 3)
        assertEquals(0, store.nextSequence(day))
        assertEquals(1, RecordStore(folder).nextSequence(day))
        assertEquals(0, store.nextSequence(day.plusDays(1)))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `반납 기록과 리포트를 함께 저장하고 끝나지 않은 시도는 지우지 않고 옮긴다`() {
        val pickup = record(SnapshotKind.PICKUP)
        store.save(pickup)
        val first = RentalReport.build(pickup, record(SnapshotKind.RETURN, t0.plusSeconds(100)))
        store.saveReturn(first.returned, first)
        assertEquals(first.hash, assertIs<Stored.Found<RentalReport>>(store.readReport("s-1")).value.hash)

        val second = RentalReport.build(pickup, record(SnapshotKind.RETURN, t0.plusSeconds(200)))
        store.saveReturn(second.returned, second)

        assertEquals(second.hash, assertIs<Stored.Found<RentalReport>>(store.readReport("s-1")).value.hash)
        assertEquals(second.returned, assertIs<Stored.Found<SnapshotRecord>>(store.readReturn("s-1")).value)
        assertTrue(File(folder, "s-1/return.abandoned-1.json").exists())
        assertTrue(File(folder, "s-1/report.abandoned-1.json").exists())
    }

    @Test
    fun `리포트와 기록이 서로 맞지 않으면 손상으로 본다`() {
        val pickup = record(SnapshotKind.PICKUP)
        store.save(pickup)
        val report = RentalReport.build(pickup, record(SnapshotKind.RETURN, t0.plusSeconds(100)))
        store.saveReturn(report.returned, report)

        // 반납 기록만 다른 시각의 온전한 기록으로 바뀌면 리포트의 무결성 값과 맞지 않는다.
        val replacement = record(SnapshotKind.RETURN, t0.plusSeconds(300))
        File(folder, "s-1/return.json").writeText(
            app.chagok.core.record.Canonical.encode(replacement.toJsonWithHash()),
        )
        assertIs<Stored.Damaged>(store.readReport("s-1"))
    }

    @Test
    fun `저장된 대여를 최근 것부터 상태와 함께 보여 준다`() {
        store.save(record(SnapshotKind.PICKUP, t0, "open"))
        store.save(record(SnapshotKind.PICKUP, t0.plusSeconds(10), "voided"))
        store.markVoid("voided", "차량 교체", t0.plusSeconds(20))
        val pickup = record(SnapshotKind.PICKUP, t0.plusSeconds(30), "done")
        store.save(pickup)
        val report = RentalReport.build(pickup, record(SnapshotKind.RETURN, t0.plusSeconds(40), "done"))
        store.saveReturn(report.returned, report)
        store.save(record(SnapshotKind.PICKUP, t0.plusSeconds(50), "bad"))
        val bad = File(folder, "bad/pickup.json")
        bad.writeText(bad.readText().replace("82.0", "99.0"))
        File(folder, "empty").mkdirs()

        val sessions = store.sessions()

        assertEquals(setOf("open", "voided", "done", "bad"), sessions.map { it.sessionId }.toSet())
        assertEquals(StoredSession.Status.PICKUP, sessions.single { it.sessionId == "open" }.status)
        assertEquals(StoredSession.Status.VOIDED, sessions.single { it.sessionId == "voided" }.status)
        assertEquals(StoredSession.Status.RETURNED, sessions.single { it.sessionId == "done" }.status)
        assertEquals(StoredSession.Status.DAMAGED, sessions.single { it.sessionId == "bad" }.status)
        assertEquals(listOf("done", "voided", "open", "bad"), sessions.map { it.sessionId }, "읽을 수 있는 기록을 시각순으로, 읽지 못한 것은 뒤에")
        assertNull(sessions.single { it.sessionId == "bad" }.recordNo)
    }

    @Test
    fun `타임라인은 줄마다 덧붙이고 잘린 줄은 건너뛰며 다음 줄을 잃지 않는다`() {
        val tpms = Telltale("TPMS", "타이어 공기압")
        val first = TimelineLine.Event(LoggedEvent(t0, null, VehicleEvent.TelltalesChanged(setOf(tpms))))
        val gap = TimelineLine.Gap(RecordGap(t0.plusSeconds(1), t0.plusSeconds(2)))
        val last = TimelineLine.Event(LoggedEvent(t0.plusSeconds(3), null, VehicleEvent.TelltalesChanged(emptySet())))
        assertEquals(emptyList(), store.readTimeline("s-1"))

        store.appendTimeline("s-1", first)
        store.appendTimeline("s-1", TimelineLine.Event(LoggedEvent(t0, null, VehicleEvent.DoorChanged(app.chagok.core.model.Area.FRONT_LEFT, true))))
        store.appendTimeline("s-1", gap)
        val file = File(folder, "s-1/timeline.jsonl")
        assertEquals(2, file.readLines().size, "문 열림은 남지 않는다")

        file.appendText("""{"at":"2026-11-03T00:12:0""")
        store.appendTimeline("s-1", last)

        assertEquals(listOf(first, gap, last), store.readTimeline("s-1"))
    }

    @Test
    fun `생존 시각을 적고 읽는다`() {
        assertNull(store.readAlive("s-1"))
        store.saveAlive("s-1", t0)
        assertEquals(t0, store.readAlive("s-1"))
        File(folder, "s-1/alive.txt").writeText("쓰다 꺼짐")
        assertNull(store.readAlive("s-1"))
    }

    // 보관 기간

    private val now = t0.plus(java.time.Duration.ofDays(200))

    private fun returned(sessionId: String, at: Instant) {
        val pickup = record(SnapshotKind.PICKUP, at, sessionId)
        store.save(pickup)
        val report = RentalReport.build(pickup, record(SnapshotKind.RETURN, at.plusSeconds(100), sessionId))
        store.saveReturn(report.returned, report)
    }

    @Test
    fun `보관 기간이 지난 대여만 통째로 지우고 임시 폴더를 남기지 않는다`() {
        returned("old", now.minus(java.time.Duration.ofDays(181)))
        store.appendTimeline("old", TimelineLine.Gap(RecordGap(t0, t0.plusSeconds(1))))
        returned("recent", now.minus(java.time.Duration.ofDays(179)))

        assertEquals(1, store.purgeExpired(now))

        assertTrue(!File(folder, "old").exists())
        assertTrue(!File(folder, ".purging").exists())
        assertEquals(listOf("recent"), store.sessions().map { it.sessionId })
        assertEquals(0, store.purgeExpired(now), "다시 불러도 더 지울 것이 없다")
    }

    @Test
    fun `대여의 나이는 마지막 기록으로 잰다`() {
        val pickedUp = now.minus(java.time.Duration.ofDays(300))
        val pickup = record(SnapshotKind.PICKUP, pickedUp, "long")
        store.save(pickup)
        val report = RentalReport.build(pickup, record(SnapshotKind.RETURN, now.minus(java.time.Duration.ofDays(10)), "long"))
        store.saveReturn(report.returned, report)
        store.save(record(SnapshotKind.PICKUP, pickedUp, "voided"))
        store.markVoid("voided", "차량 교체", now.minus(java.time.Duration.ofDays(1)))

        assertEquals(0, store.purgeExpired(now))
        assertEquals(setOf("long", "voided"), store.sessions().map { it.sessionId }.toSet())
    }

    @Test
    fun `열린 대여와 화면에 있는 대여는 아무리 오래되어도 지우지 않는다`() {
        val old = now.minus(java.time.Duration.ofDays(400))
        store.save(record(SnapshotKind.PICKUP, old, "open"))
        store.saveSession(SavedSession(RentalState.IN_RENTAL, "open", "0007-1103-A1", old))
        assertEquals(0, store.purgeExpired(now))
        assertIs<Stored.Found<SnapshotRecord>>(store.readPickup("open"))

        returned("shown", old)
        store.saveSession(SavedSession(RentalState.RETURNED, "shown", "0007-1103-A1", old))
        assertEquals(1, store.purgeExpired(now), "화면에서 닫히지 않은 리포트는 남기고 앞 대여는 지운다")
        assertIs<Stored.Found<RentalReport>>(store.readReport("shown"))
        assertEquals(Stored.Missing, store.readPickup("open"))
    }

    @Test
    fun `어느 대여가 열려 있는지 모르면 아무것도 지우지 않는다`() {
        store.save(record(SnapshotKind.PICKUP, now.minus(java.time.Duration.ofDays(400)), "old"))
        File(folder, "session.json").writeText("{")
        assertEquals(0, store.purgeExpired(now))
        assertIs<Stored.Found<SnapshotRecord>>(store.readPickup("old"))

        store.saveSession(SavedSession(RentalState.IN_RENTAL, null, null, now))
        assertEquals(0, store.purgeExpired(now), "열렸는데 번호가 없다")
        assertIs<Stored.Found<SnapshotRecord>>(store.readPickup("old"))
    }

    @Test
    fun `내용을 읽지 못하는 대여는 파일 시각으로 잰다`() {
        store.save(record(SnapshotKind.PICKUP, now.minus(java.time.Duration.ofDays(1)), "bad"))
        val bad = File(folder, "bad/pickup.json")
        bad.writeText("{")
        bad.setLastModified(now.minus(java.time.Duration.ofDays(200)).toEpochMilli())
        File(folder, "bad").setLastModified(now.minus(java.time.Duration.ofDays(200)).toEpochMilli())

        store.save(record(SnapshotKind.PICKUP, now.minus(java.time.Duration.ofDays(1)), "fresh-bad"))
        File(folder, "fresh-bad/pickup.json").writeText("{")
        File(folder, "fresh-bad/pickup.json").setLastModified(now.minus(java.time.Duration.ofDays(1)).toEpochMilli())
        File(folder, "fresh-bad").setLastModified(now.minus(java.time.Duration.ofDays(1)).toEpochMilli())

        assertEquals(1, store.purgeExpired(now), "오래된 쪽만 지운다")
        assertTrue(!File(folder, "bad").exists())
        assertTrue(File(folder, "fresh-bad").exists())
    }

    @Test
    fun `지우다 꺼져 남은 폴더는 다음에 마저 지우고 목록에 보이지 않는다`() {
        returned("recent", now)
        val left = File(folder, ".purging/old")
        left.mkdirs()
        File(left, "pickup.json").writeText("{")

        assertEquals(listOf("recent"), store.sessions().map { it.sessionId }, "지우는 중인 대여는 목록에 없다")
        store.purgeExpired(now)
        assertTrue(!File(folder, ".purging").exists())
        assertEquals(listOf("recent"), store.sessions().map { it.sessionId })
    }

    @Test
    fun `기간이 지난 거절 기록과 순번도 지운다`() {
        val oldAt = now.minus(java.time.Duration.ofDays(181))
        store.saveDecline(DeclinedNotice(oldAt, "V-0007"))
        store.saveDecline(DeclinedNotice(now.minus(java.time.Duration.ofDays(1)), "V-0007"))
        val oldDay = LocalDate.of(2026, 1, 1)
        val today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        store.nextSequence(oldDay)
        store.nextSequence(today)

        store.purgeExpired(now)

        assertEquals(listOf(DeclinedNotice(now.minus(java.time.Duration.ofDays(1)), "V-0007")), store.declines())
        assertTrue(!File(folder, "sequence-$oldDay.txt").exists())
        assertTrue(File(folder, "sequence-$today.txt").exists())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `거절 기록이 모두 지나면 파일을 지운다`() {
        store.saveDecline(DeclinedNotice(now.minus(java.time.Duration.ofDays(181)), "V-0007"))
        store.purgeExpired(now)
        assertTrue(!File(folder, "declined.jsonl").exists())
        assertEquals(emptyList(), store.declines())
    }

    @Test
    fun `일부를 지우지 못해도 예외를 던지지 않고 나머지를 계속한다`() {
        returned("old", now.minus(java.time.Duration.ofDays(400)))
        returned("locked", now.minus(java.time.Duration.ofDays(400)))
        // Windows에서는 파일을 잡고 있으면 폴더를 옮기지 못한다. 옮기지 못한 대여는 온전히 남아 있어야 한다. 다른 운영체제에서는 함께 지워진다.
        FileOutputStream(File(folder, "locked/alive.txt")).use { store.purgeExpired(now) }

        assertTrue(!File(folder, "old").exists())
        store.sessions().forEach { assertIs<Stored.Found<RentalReport>>(store.readReport(it.sessionId)) }
    }
}
