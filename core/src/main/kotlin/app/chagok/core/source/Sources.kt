package app.chagok.core.source

import app.chagok.core.model.GeoPoint
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import kotlinx.coroutines.flow.Flow

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
     * 문·잠금·창문·라이트·주차 브레이크·착석·소모품·경고등·에어백 변화.
     * [VehicleEvent.Moved]와 [VehicleEvent.Note]는 내보내지 않는다.
     */
    fun changes(): Flow<VehicleEvent>
}

interface LocationSource {
    suspend fun current(): GeoPoint?

    /** 세션 동안 계속 쌓이므로 수십 초 간격 정도로 성기게 내보낸다. */
    fun updates(): Flow<GeoPoint>
}
