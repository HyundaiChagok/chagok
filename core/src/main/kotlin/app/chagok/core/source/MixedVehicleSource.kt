package app.chagok.core.source

import app.chagok.core.model.StateItem
import app.chagok.core.model.VehicleEvent
import app.chagok.core.model.VehicleState
import app.chagok.core.model.item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge

/**
 * 두 소스를 항목별로 합친다. [overrides]에 든 항목은 [secondary] 값을, 나머지는 [primary] 값을 쓴다.
 *
 * 시연 모드(기능 정의서 F-SYS-07)에서 에뮬레이터가 바꿀 수 없는 값(연료, 소모품, 타이어 등)만
 * 가짜 차량의 대본 값으로 채우고, 문·좌석처럼 바꿀 수 있는 값은 SDK에서 읽는다.
 */
class MixedVehicleSource(
    private val primary: VehicleSource,
    private val secondary: VehicleSource,
    val overrides: Set<StateItem>,
) : VehicleSource {
    override suspend fun state(): VehicleState = primary.state().overlay(secondary.state(), overrides)

    override fun changes(): Flow<VehicleEvent> = merge(
        primary.changes().filter { it.item !in overrides },
        secondary.changes().filter { it.item in overrides },
    )
}
