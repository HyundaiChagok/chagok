package app.chagok.core.model

/** 차대번호(VIN). 기록의 차량 식별에 쓰고, 화면에는 끝 6자리만 보여 준다. */
object Vin {
    // ISO 3779: 17자리, I·O·Q 없음
    private val FORMAT = Regex("[A-HJ-NPR-Z0-9]{17}")

    private const val SHOWN = 6

    /** SDK가 준 문자열에서 차대번호를 꺼낸다. 형식이 맞지 않으면 null이다. 에뮬레이터는 따옴표가 붙은 "invalid"를 준다. */
    fun parse(raw: String?): String? = raw?.trim()?.trim('"')?.uppercase()?.takeIf(FORMAT::matches)

    /** 화면에 보일 차량 표시. 차대번호면 끝 6자리만, 업체 등록 번호(V-0007)면 그대로 쓴다. */
    fun label(vehicleId: String): String = if (FORMAT.matches(vehicleId)) "…" + vehicleId.takeLast(SHOWN) else vehicleId
}
