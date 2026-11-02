package app.chagok.core.source

import app.chagok.core.model.GeoPoint
import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.model.item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import java.time.Instant

/**
 * 차 데이터를 읽는 유일한 통로. Pleos SDK 구현과 가짜 구현이 이 인터페이스를 따른다.
 *
 * Pleos SDK는 값을 콜백(onComplete/onFailed)으로 돌려주므로, 구현체는 get 호출을
 * suspendCancellableCoroutine으로, register/unregister 리스너를 callbackFlow로 감싼다.
 */
interface VehicleSource {
    /** 지금 차 상태. 읽지 못한 항목은 null로 두고, 지원하지 않는지 읽지 못했는지를 [VehicleState.unavailable]에 적는다. */
    suspend fun state(): VehicleState

    /**
     * 문·잠금·창문·트렁크·충전구·라이트·주차 브레이크·착석·잔량·소모품·경고등·에어백 변화.
     * [VehicleEvent.Moved]와 [VehicleEvent.Note]는 내보내지 않는다.
     */
    fun changes(): Flow<VehicleEvent>

    /** [items]의 변화만 구독한다. 대여 중 기록처럼 일부만 필요할 때 쓴다. 구현체는 나머지 값을 아예 듣지 않는 것이 좋다. */
    fun changes(items: Set<StateItem>): Flow<VehicleEvent> = changes().filter { it.item in items }

    /** 항목마다 SDK가 마지막으로 값이나 실패를 알려 온 시각(직원 화면의 진단). 가짜 차량처럼 SDK를 쓰지 않으면 비어 있다. */
    fun lastCallbacks(): Map<StateItem, Instant> = emptyMap()

    /** 차대번호. 이 차가 주지 않거나 읽지 못하면 null이고, 부르는 쪽이 업체 등록 번호로 대신한다. */
    suspend fun vin(): String? = null
}

interface LocationSource {
    suspend fun current(): GeoPoint?

    /** 세션 동안 계속 쌓이므로 수십 초 간격 정도로 성기게 내보낸다. */
    fun updates(): Flow<GeoPoint>
}
