package app.chagok.rental

import android.util.Log
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotKind
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.Rental
import app.chagok.core.rental.RentalReport
import app.chagok.core.rental.RentalState
import app.chagok.core.rental.TimelineJson
import app.chagok.core.rental.TimelineLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 저장소에서 읽은 결과. 없는 것과 내용이 맞지 않는 것을 나눈다. 맞지 않는 기록을 '없음'으로 보고 새 기록을 시작하면 안 되기 때문이다. */
sealed interface Stored<out T> {
    data class Found<T>(val value: T) : Stored<T>
    data object Missing : Stored<Nothing>
    data class Damaged(val reason: String) : Stored<Nothing>
}

/** session.json. 지금 어느 대여가 어느 상태인지. 앱을 다시 켜면 이 파일로 이어 간다. */
data class SavedSession(val state: RentalState, val sessionId: String?, val recordNo: String?, val at: Instant) {
    /** 인수 기록을 남겼고 반납을 아직 마치지 못한 대여인지. 이 동안 대여 중 기록을 이어 간다. */
    val isOpen: Boolean get() = state == RentalState.IN_RENTAL || state == RentalState.RETURN_CHECK || state == RentalState.RETURN
}

/** 직원 화면의 저장 기록 한 줄 */
data class StoredSession(val sessionId: String, val recordNo: String?, val pickupAt: Instant?, val status: Status) {
    /** PICKUP은 인수 기록만 있는 대여, DAMAGED는 저장된 내용이 무결성 값과 맞지 않는 대여다. */
    enum class Status { PICKUP, RETURNED, VOIDED, DAMAGED }
}

/** 고지 화면에서 기록을 거절한 사실 한 건. 시각과 차량만 남기고 개인정보는 없다. 직원 화면의 기록 목록에 보인다. */
data class DeclinedNotice(val at: Instant, val vehicle: String)

/**
 * 기록과 리포트를 앱 전용 저장소에 남긴다(기능 정의서 F-SYS-02). 기록은 같은 이름의 파일을 덮어쓰지 않아 추가만 하는 저장소가 되고,
 * 저장을 마치지 못한 것은 잘린 채 남지 않게 임시 파일 + fsync + rename으로 쓴다. 서버 업로드는 R2에서 붙인다.
 *
 * 쓰기에 실패하면 [IOException]을 던진다. 부르는 쪽이 받아 화면에 알린다.
 *
 * ```
 * session.json, sequence-2026-11-05.txt, declined.jsonl
 * 〈sessionId〉/pickup.json, return.json, report.json, void.json, timeline.jsonl, alive.txt
 * .purging/〈sessionId〉/… (지우는 중인 대여)
 * ```
 */
class RecordStore(private val dir: File) {
    /** 인수 기록을 남긴다. 같은 내용이면 아무 일도 하지 않고, 다른 내용이 이미 있으면 덮어쓰지 않고 실패한다. */
    fun save(record: SnapshotRecord) = writeOnce(recordPath(record.sessionId, record.kind), Canonical.encode(record.toJsonWithHash()))

    /**
     * 반납 기록과 리포트를 함께 남긴다. session.json이 RETURNED로 바뀌어야 끝난 것이므로, 이 대여에 이미 있는 파일은
     * 앱이 그 사이에 꺼졌거나 저장하다 실패한 시도다. 아무도 보지 못한 기록이지만 지우지 않고 옆으로 옮긴다.
     */
    fun saveReturn(record: SnapshotRecord, report: RentalReport) {
        setAside(recordPath(record.sessionId, SnapshotKind.RETURN))
        setAside(reportPath(record.sessionId))
        writeOnce(recordPath(record.sessionId, SnapshotKind.RETURN), Canonical.encode(record.toJsonWithHash()))
        writeOnce(reportPath(record.sessionId), Canonical.encode(JsonObject(report.toJson() + ("hash" to JsonPrimitive(report.hash)))))
    }

    /** 앱이 void.json을 쓴 직후 꺼졌다가 다시 무효 처리하면 처음 사유를 그대로 둔다. */
    fun markVoid(sessionId: String, reason: String, at: Instant) {
        val file = File(dir, "$sessionId/$VOID_FILE")
        if (!file.exists()) writeAtomic(file, Canonical.encode(buildJsonObject { put("reason", reason); put("at", at.toString()) }))
    }

    /** 그날 몇 번째 기록인지. 기록 번호의 순번이다. */
    @Synchronized
    fun nextSequence(day: LocalDate): Int {
        val counter = File(dir, "$SEQUENCE_PREFIX$day.txt")
        val next = counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.plus(1) ?: 0
        writeAtomic(counter, next.toString())
        return next
    }

    fun saveSession(session: SavedSession) {
        val json = buildJsonObject {
            put("state", session.state.name)
            put("sessionId", session.sessionId)
            put("recordNo", session.recordNo)
            put("at", session.at.toString())
        }
        writeAtomic(File(dir, SESSION_FILE), Canonical.encode(json))
    }

    fun readSession(): Stored<SavedSession> = read(SESSION_FILE) { json ->
        SavedSession(
            state = RentalState.valueOf(json.getValue("state").jsonPrimitive.content),
            sessionId = json["sessionId"]?.jsonPrimitive?.contentOrNull,
            recordNo = json["recordNo"]?.jsonPrimitive?.contentOrNull,
            at = Instant.parse(json.getValue("at").jsonPrimitive.content),
        )
    }

    fun readPickup(sessionId: String): Stored<SnapshotRecord> = readRecord(sessionId, SnapshotKind.PICKUP)

    fun readReturn(sessionId: String): Stored<SnapshotRecord> = readRecord(sessionId, SnapshotKind.RETURN)

    /** 저장한 리포트를 인수·반납 기록과 함께 검증해 되살린다. 셋 중 하나라도 맞지 않으면 [Stored.Damaged]다. */
    fun readReport(sessionId: String): Stored<RentalReport> {
        if (!File(dir, reportPath(sessionId)).exists()) return Stored.Missing
        val pickup = readPickup(sessionId) as? Stored.Found ?: return Stored.Damaged("인수 기록을 읽지 못했습니다")
        val returned = readReturn(sessionId) as? Stored.Found ?: return Stored.Damaged("반납 기록을 읽지 못했습니다")
        return read(reportPath(sessionId)) { RentalReport.fromJson(it, pickup.value, returned.value) }
    }

    /** 저장된 대여를 최근 것부터 [limit]개. 내용까지 검증하므로 메인 스레드 밖에서 부른다. */
    fun sessions(limit: Int = 20): List<StoredSession> =
        sessionFolders()
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { session(it.name) }
            .sortedByDescending { it.pickupAt ?: Instant.MIN }

    /** 대여 중 변화를 바로 덧붙인다. 앱이 곧바로 꺼져도 남도록 줄마다 fsync한다. */
    fun appendTimeline(sessionId: String, line: TimelineLine) {
        val text = TimelineJson.encode(line) ?: return
        append(File(dir, "$sessionId/$TIMELINE_FILE"), text)
    }

    /** 고지 화면에서 기록을 거절한 사실을 덧붙인다. */
    fun saveDecline(decline: DeclinedNotice) {
        append(File(dir, DECLINED_FILE), Canonical.encode(buildJsonObject { put("at", decline.at.toString()); put("vehicle", decline.vehicle) }))
    }

    /** 거절 기록을 최근 것부터 [limit]개. 읽을 수 없는 줄은 건너뛴다. */
    fun declines(limit: Int = 20): List<DeclinedNotice> = try {
        File(dir, DECLINED_FILE).takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty().mapNotNull { line ->
            try {
                Json.parseToJsonElement(line).jsonObject.let {
                    DeclinedNotice(Instant.parse(it.getValue("at").jsonPrimitive.content), it.getValue("vehicle").jsonPrimitive.content)
                }
            } catch (e: Exception) {
                null
            }
        }.asReversed().take(limit)
    } catch (e: IOException) {
        emptyList()
    }

    private fun append(file: File, text: String) {
        file.parentFile?.mkdirs()
        // 앱이 쓰는 도중 꺼져 마지막 줄이 잘렸으면 줄을 바꾼 뒤 잇는다. 잘린 줄 때문에 새 줄까지 읽지 못하게 되면 안 된다.
        val prefix = if (endsMidLine(file)) "\n" else ""
        FileOutputStream(file, true).use { out ->
            out.write("$prefix$text\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** 읽을 수 없는 줄은 건너뛴다. 잘린 마지막 줄은 앱이 꺼진 시점이라 어차피 기록 공백 안에 있다. */
    fun readTimeline(sessionId: String): List<TimelineLine> = try {
        File(dir, "$sessionId/$TIMELINE_FILE").takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty().mapNotNull(TimelineJson::decode)
    } catch (e: IOException) {
        emptyList()
    }

    /** 앱이 살아 있던 마지막 시각. 다음에 앱을 켰을 때 기록 공백의 시작이 된다. */
    fun saveAlive(sessionId: String, at: Instant) = writeAtomic(File(dir, "$sessionId/$ALIVE_FILE"), at.toString())

    fun readAlive(sessionId: String): Instant? = try {
        File(dir, "$sessionId/$ALIVE_FILE").takeIf { it.exists() }?.readText()?.trim()?.let(Instant::parse)
    } catch (e: Exception) {
        null
    }

    /**
     * 보관 기간([keep])이 지난 기록을 지운다(고지 화면의 '180일 보관'). 앱을 켤 때와 직원이 기록 목록을 열 때 부른다. 지운 대여 수를 돌려준다.
     * 대여의 나이는 마지막 기록 시각(인수·반납·무효)으로 재고, 내용을 읽지 못하는 대여는 파일을 마지막으로 쓴 시각으로 잰다.
     * session.json이 가리키는 대여는 건드리지 않는다. session.json을 믿을 수 없으면 어느 대여가 열려 있는지 모르므로 아무것도 지우지 않는다.
     *
     * 대여 폴더는 먼저 [TRASH_FOLDER]로 한 번에 옮긴 뒤 지운다. 지우다 꺼져도 반쯤 지워진 대여가 목록에 '읽지 못함'으로 남지 않고, 남은 것은 다음에 마저 지운다.
     * 하나를 지우지 못해도 나머지는 계속한다. 기록을 지우지 못했다고 앱이 멈추면 안 된다.
     */
    fun purgeExpired(now: Instant, keep: Duration = Duration.ofDays(Rental.RETENTION_DAYS)): Int {
        emptyTrash()
        val saved = readSession()
        if (saved is Stored.Damaged) return 0
        val current = (saved as? Stored.Found)?.value
        if (current?.isOpen == true && current.sessionId == null) return 0
        val cutoff = now.minus(keep)
        val purged = sessionFolders().count { folder ->
            folder.name != current?.sessionId && lastRecorded(folder).let { it != null && it < cutoff } && moveToTrash(folder)
        }
        emptyTrash()
        purgeDeclines(cutoff)
        purgeSequences(cutoff)
        if (purged > 0) Log.i(TAG, "보관 기간이 지난 대여 ${purged}건을 지웠습니다")
        return purged
    }

    private fun sessionFolders(): List<File> = dir.listFiles { file -> file.isDirectory && file.name != TRASH_FOLDER }.orEmpty().toList()

    // 마지막 기록 시각. 읽을 수 있는 기록이 하나도 없으면 파일 시각으로 잰다. 그것도 모르면 null이고 지우지 않는다.
    private fun lastRecorded(folder: File): Instant? {
        val id = folder.name
        val voidedAt = try {
            File(folder, VOID_FILE).takeIf { it.exists() }?.let { Instant.parse(Json.parseToJsonElement(it.readText(Charsets.UTF_8)).jsonObject.getValue("at").jsonPrimitive.content) }
        } catch (e: Exception) {
            null
        }
        val recorded = listOfNotNull(
            (readPickup(id) as? Stored.Found)?.value?.capturedAt,
            (readReturn(id) as? Stored.Found)?.value?.capturedAt,
            voidedAt,
        ).maxOrNull()
        if (recorded != null) return recorded
        return folder.walkTopDown().maxOfOrNull { it.lastModified() }?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    }

    private fun moveToTrash(folder: File): Boolean = try {
        val trash = File(dir, TRASH_FOLDER)
        if (!trash.isDirectory && !trash.mkdirs()) throw IOException("폴더를 만들지 못했습니다: $trash")
        Files.move(folder.toPath(), File(trash, folder.name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncFolder(dir)
        true
    } catch (e: IOException) {
        Log.w(TAG, "지난 기록을 옮기지 못했습니다: ${e.message}")
        false
    }

    private fun emptyTrash() {
        val trash = File(dir, TRASH_FOLDER)
        if (trash.exists() && !trash.deleteRecursively()) Log.w(TAG, "지난 기록을 다 지우지 못했습니다. 다음에 다시 지웁니다.")
    }

    // 거절 기록도 기록이라 같이 지운다. 지울 줄이 있을 때만 다시 쓴다. 읽을 수 없는 줄은 시각을 모르니 그때 함께 버린다(화면에도 보이지 않는 줄이다).
    private fun purgeDeclines(cutoff: Instant) {
        val file = File(dir, DECLINED_FILE)
        try {
            if (!file.exists()) return
            val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val kept = lines.filter { line -> declineAt(line)?.let { it >= cutoff } == true }
            if (lines.none { line -> declineAt(line)?.let { it < cutoff } == true }) return
            if (kept.isEmpty()) file.delete() else writeAtomic(file, kept.joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            Log.w(TAG, "지난 거절 기록을 지우지 못했습니다: ${e.message}")
        }
    }

    private fun declineAt(line: String): Instant? = try {
        Instant.parse(Json.parseToJsonElement(line).jsonObject.getValue("at").jsonPrimitive.content)
    } catch (e: Exception) {
        null
    }

    // 지난 날의 순번은 다시 쓰이지 않는다.
    private fun purgeSequences(cutoff: Instant) {
        dir.listFiles { file -> file.name.startsWith(SEQUENCE_PREFIX) && file.name.endsWith(".txt") }.orEmpty().forEach { file ->
            val day = try {
                LocalDate.parse(file.name.removePrefix(SEQUENCE_PREFIX).removeSuffix(".txt"))
            } catch (e: Exception) {
                return@forEach
            }
            // 하루치 여유를 둔다. 시간대가 달라도 그날의 순번을 지우지 않는다.
            if (day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() < cutoff) file.delete()
        }
    }

    /** 앱 시작 때 부른다. 쓰다 꺼져 남은 임시 파일을 지운다. 진짜 파일은 rename 전이라 그대로다. */
    fun removeUnfinishedWrites() {
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
    }

    private fun session(sessionId: String): StoredSession? {
        val pickup = readPickup(sessionId)
        val record = (pickup as? Stored.Found)?.value
        val status = when {
            pickup is Stored.Missing -> return null
            File(dir, "$sessionId/$VOID_FILE").exists() -> StoredSession.Status.VOIDED
            pickup is Stored.Damaged -> StoredSession.Status.DAMAGED
            else -> when (readReport(sessionId)) {
                is Stored.Found -> StoredSession.Status.RETURNED
                is Stored.Damaged -> StoredSession.Status.DAMAGED
                Stored.Missing -> StoredSession.Status.PICKUP
            }
        }
        return StoredSession(sessionId, record?.recordNo, record?.capturedAt, status)
    }

    // fromJson은 hash가 없으면 검사를 건너뛰므로, 저장한 기록은 hash가 있는지부터 본다. 지운 값으로 검사를 피할 수 없게 한다.
    private fun readRecord(sessionId: String, kind: SnapshotKind): Stored<SnapshotRecord> = read(recordPath(sessionId, kind)) { json ->
        require("hash" in json) { "무결성 값이 없습니다" }
        SnapshotRecord.fromJson(json).also { require(it.sessionId == sessionId && it.kind == kind) { "다른 기록입니다" } }
    }

    private fun <T> read(path: String, parse: (JsonObject) -> T): Stored<T> {
        val file = File(dir, path)
        if (!file.exists()) return Stored.Missing
        return try {
            Stored.Found(parse(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject))
        } catch (e: Exception) {
            // 화면에는 사유를 보여 주지 않는다(판단으로 읽힐 수 있다). 진단은 로그로 한다.
            Log.w(TAG, "$path: ${e.message}")
            Stored.Damaged(e.message ?: e::class.java.simpleName)
        }
    }

    private fun recordPath(sessionId: String, kind: SnapshotKind) = "$sessionId/${kind.name.lowercase()}.json"

    private fun reportPath(sessionId: String) = "$sessionId/report.json"

    private fun writeOnce(path: String, text: String) {
        val file = File(dir, path)
        if (!file.exists()) return writeAtomic(file, text)
        if (file.readText(Charsets.UTF_8) != text) throw IOException("이미 다른 내용이 있어 덮어쓰지 않습니다: $path")
    }

    private fun setAside(path: String) {
        val file = File(dir, path)
        if (!file.exists()) return
        val aside = generateSequence(1) { it + 1 }
            .map { File(file.parentFile, "${file.nameWithoutExtension}.abandoned-$it.${file.extension}") }
            .first { !it.exists() }
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    // 임시 파일에 다 쓰고 fsync한 뒤 rename한다. 어느 순간에 꺼져도 이전 파일이나 새 파일 가운데 하나가 온전히 남는다.
    private fun writeAtomic(file: File, text: String) {
        val folder = file.parentFile ?: dir
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("폴더를 만들지 못했습니다: $folder")
        val temp = File(folder, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        syncFolder(folder)
    }

    // rename까지 디스크에 남긴다. Windows에서 도는 단위 테스트처럼 폴더를 열 수 없는 환경에서는 건너뛴다.
    private fun syncFolder(folder: File) {
        try {
            FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // 내용은 이미 fsync했다.
        }
    }

    private fun endsMidLine(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
    }

    companion object {
        /** filesDir 아래 기록 폴더 이름 */
        const val FOLDER = "records"

        private const val TAG = "ChagokStore"
        private const val SESSION_FILE = "session.json"
        private const val TIMELINE_FILE = "timeline.jsonl"
        private const val DECLINED_FILE = "declined.jsonl"
        private const val ALIVE_FILE = "alive.txt"
        private const val TEMP_SUFFIX = ".tmp"
        private const val VOID_FILE = "void.json"
        private const val SEQUENCE_PREFIX = "sequence-"
        private const val TRASH_FOLDER = ".purging"
    }
}
