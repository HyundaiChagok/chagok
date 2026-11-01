package app.chagok.pleos

import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import app.chagok.core.model.Telltale

/**
 * 리포트에 남길 경고등과 화면에 보일 이름. 방향지시등·전조등·주행 모드처럼 상태를 알리는 표시등과,
 * 문 열림·안전띠처럼 반납 체크가 따로 보는 것은 남기지 않는다.
 */
internal val WARNING_TELLTALES: Map<TelltaleType, String> = mapOf(
    TelltaleType.ENGINE to "엔진 점검",
    TelltaleType.ENGINE_OIL_PRESSURE to "엔진오일 압력",
    TelltaleType.ENGINE_TEMPERATURE to "엔진 온도",
    TelltaleType.TPMS to "타이어 공기압",
    TelltaleType.ABS to "ABS",
    TelltaleType.ESC to "차체 자세 제어(ESC)",
    TelltaleType.MDPS to "전동식 조향(MDPS)",
    TelltaleType.AIRBAG to "에어백",
    TelltaleType.LOW_VOLT_BATTERY to "충전 경고",
    TelltaleType.MASTER_WARNING to "마스터 경고",
    TelltaleType.LOW_FUEL to "연료 부족",
    TelltaleType.LOW_FUEL_FCEV to "수소 부족",
    TelltaleType.LOW_EV_BATTERY to "배터리 부족",
    TelltaleType.EV_SYSTEM to "전기차 시스템",
    TelltaleType.HEV_SYSTEM to "하이브리드 시스템",
    TelltaleType.FCEV_SYSTEM to "수소전기차 시스템",
    TelltaleType.EREV_SYSTEM to "EREV 시스템",
    TelltaleType.POWER_DOWN to "출력 제한",
    TelltaleType.RBS to "회생 제동",
    TelltaleType.PARTICLE_FILTER to "매연 필터",
    TelltaleType.SCR to "요소수",
    TelltaleType.WATER_SEPARATOR to "연료 필터 수분",
    TelltaleType.H2_SENSOR to "수소 센서",
    TelltaleType.H2_LEAK to "수소 누출",
)

internal fun Telltales.warnings(): Set<Telltale> = telltales
    .filter { it.state != TelltaleState.OFF }
    .mapNotNull { data -> WARNING_TELLTALES[data.type]?.let { Telltale(data.type.name, it) } }
    .toSet()
