package app.chagok.core.rental

/** 리포트와 음성에 쓰지 않는 말. 사실만 적고 원인·책임·청구는 판단하지 않는다(요구사항 SFR-12, SAF-05). */
object Wording {
    val FORBIDDEN: List<String> = listOf(
        "책임", "과실", "고의", "위반", "벌금", "페널티", "고장", "사고", "파손", "손상", "부주의", "청구",
    )

    /** 금지어가 들어가도 되는 고정 문구. 판단하지 않는다는 안내뿐이다. */
    val ALLOWED: List<String> = listOf("청구 확정은 업체", "고객 책임이라는 뜻이 아닙니다")

    fun violations(text: String): List<String> {
        val checked = ALLOWED.fold(text) { rest, phrase -> rest.replace(phrase, "") }
        return FORBIDDEN.filter { it in checked }
    }
}
