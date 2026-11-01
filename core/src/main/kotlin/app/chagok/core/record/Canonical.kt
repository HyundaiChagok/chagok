package app.chagok.core.record

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 키를 정렬하고 공백 없이 쓴 JSON. 같은 내용이면 차에서 만들든 서버에서 만들든 같은 문자열이 나오므로
 * 무결성 값의 입력으로 쓴다.
 */
object Canonical {
    fun encode(element: JsonElement): String = sorted(element).toString()

    fun sha256(text: String): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 화면에 보일 앞 8자리를 4자리씩 끊는다. 예: "a3f9 08c1" */
    fun short(hash: String): String = hash.removePrefix("sha256:").take(8).chunked(4).joinToString(" ")

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) -> sorted(value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
}
