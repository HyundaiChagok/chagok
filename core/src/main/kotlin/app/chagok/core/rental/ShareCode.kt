package app.chagok.core.rental

import app.chagok.core.inspect.Verdict
import app.chagok.core.record.Canonical
import app.chagok.core.record.SnapshotRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

/**
 * 서버 없이 고객 폰으로 리포트를 넘기는 데이터 QR(기능 정의서 F-SHR-03).
 *
 * 정규화한 리포트 JSON을 deflate로 압축해 base64url로 바꾸고 링크의 # 뒤에 붙인다. # 뒤는 브라우저 밖으로 전송되지 않는다.
 * 리포트 웹은 풀어낸 문자열의 SHA-256을 계산해 차 화면의 무결성 값과 같은지 보여 준다.
 */
object ShareCode {
    const val FORMAT = 1
    const val MAX_BYTES = 1_000

    fun url(baseUrl: String, report: RentalReport): String = link(baseUrl, encode(report))

    fun url(baseUrl: String, record: SnapshotRecord): String = link(baseUrl, encode(record))

    /**
     * "1.〈base64url〉" 꼴이고 1은 형식 버전이다. 한도를 넘으면 '변화 없음' 줄을 항목 키만 남긴 요약본을 담는다.
     * 요약본도 한도를 넘으면 예외를 던지므로, 화면은 [url]이 실패하면 QR 대신 안내를 보여 준다.
     */
    fun encode(report: RentalReport): String {
        var bytes = deflate(Canonical.encode(report.toJson()))
        if (bytes.size > MAX_BYTES) bytes = deflate(Canonical.encode(summary(report)))
        return pack(bytes)
    }

    /** 인수 기록 한 건. 고객은 인수 때도 같은 기록을 받는다(요구사항 SFR-22). 리포트 웹은 schema로 둘을 구분한다. */
    fun encode(record: SnapshotRecord): String = pack(deflate(Canonical.encode(record.toJson())))

    private fun link(baseUrl: String, code: String) = "${baseUrl.trimEnd('/')}/r#d=$code"

    private fun pack(bytes: ByteArray): String {
        check(bytes.size <= MAX_BYTES) { "QR에 담기에는 기록이 깁니다: ${bytes.size}바이트" }
        return "$FORMAT." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 리포트 웹이 하는 일과 같다. 받은 JSON 문자열을 그대로 돌려주므로, 이 문자열의 SHA-256이 리포트 무결성 값이다. */
    fun decode(code: String): String {
        val parts = code.split('.', limit = 2)
        require(parts.size == 2 && parts[0] == FORMAT.toString()) { "모르는 데이터 QR 형식입니다" }
        return InflaterInputStream(Base64.getUrlDecoder().decode(parts[1]).inputStream()).readBytes().toString(Charsets.UTF_8)
    }

    private fun summary(report: RentalReport): JsonObject {
        val full = report.toJson()
        val (unchanged, rows) = full.getValue("rows").jsonArray.partition {
            (it as JsonObject)["verdict"] == JsonPrimitive(Verdict.UNCHANGED.name)
        }
        return buildJsonObject {
            full.filterKeys { it != "rows" && it != "schema" }.forEach { (key, value) -> put(key, value) }
            put("schema", "report-summary/2")
            put("reportHash", report.hash)
            put("rows", JsonArray(rows))
            put("unchanged", JsonArray(unchanged.map { (it as JsonObject).getValue("item") }))
        }
    }

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
