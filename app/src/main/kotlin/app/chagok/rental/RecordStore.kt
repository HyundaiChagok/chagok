package app.chagok.rental

import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.RentalReport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * 기록과 리포트를 앱 전용 저장소에 남긴다(기능 정의서 F-SYS-02). 같은 이름의 파일은 덮어쓰지 않아 추가만 하는 저장소가 된다.
 * 서버 업로드는 R2에서 붙인다.
 */
class RecordStore(private val dir: File) {
    fun save(record: SnapshotRecord) = write("${record.sessionId}/${record.kind.name.lowercase()}.json", record.toJsonWithHash())

    fun save(report: RentalReport) =
        write("${report.pickup.sessionId}/report.json", JsonObject(report.toJson() + ("hash" to JsonPrimitive(report.hash))))

    fun markVoid(sessionId: String, reason: String, at: Instant) =
        write("$sessionId/void.json", buildJsonObject { put("reason", reason); put("at", at.toString()) })

    /** 그날 몇 번째 기록인지. 기록 번호의 순번이다. */
    @Synchronized
    fun nextSequence(day: LocalDate): Int {
        dir.mkdirs()
        val counter = File(dir, "sequence-$day.txt")
        val next = counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.plus(1) ?: 0
        counter.writeText(next.toString())
        return next
    }

    private fun write(path: String, json: JsonObject) {
        val file = File(dir, path)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText(Canonical.encode(json))
    }
}
