package app.chagok.core.source

import app.chagok.core.rental.ReturnPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 반납 지점 길안내. Pleos NaviHelper 구현과 시험용 가짜가 이 인터페이스를 따른다.
 * 현재 위치와 고객이 직접 설정한 목적지는 읽지 않고, 어디에 도착했는지도 넘기지 않는다(기능 정의서 F-NAV-01, 요구사항 SER-02).
 */
interface ReturnNavigation {
    /** 내비 서비스에 연결됐는지. 연결되지 않으면 화면이 '반납하러 가기'를 숨긴다. */
    val connected: StateFlow<Boolean>

    /** 이 앱이 요청한 반납 지점 길안내에서 목적지에 도착하면 신호를 낸다. 고객이 직접 설정한 목적지의 도착은 내보내지 않는다. */
    val arrivals: Flow<Unit>

    /** 길안내가 없어졌을 때(고객이 취소). */
    val routeCancelled: Flow<Unit>

    /** 경로를 찾지 못했을 때 */
    val routeFailed: Flow<Unit>

    /** 이 앱이 요청한 길안내가 아직 끝나지 않았는지. */
    val guiding: Boolean get() = false

    fun connect()

    /** 남아 있는 이 앱의 길안내를 취소하고 연결을 끊는다. */
    fun disconnect()

    /** 이 앱이 요청한 길안내만 취소한다. 내비 앱이 화면 앞으로 나올 수 있다. */
    fun cancelRoute() {}

    /** [point]까지 갔을 때의 예상 배터리(%). 내비가 값을 주지 않으면 null이다. */
    suspend fun estimateArrivalPercent(point: ReturnPoint): Int?

    /** [point]까지 길안내를 시작한다. 결과는 [routeFailed]로 알린다. */
    fun startRoute(point: ReturnPoint)

    /** 내비의 근처 충전소 화면을 연다. */
    fun showNearbyChargers()
}
