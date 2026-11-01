package app.chagok.core.rental

import java.time.LocalDate
import java.util.Locale

/** 대여 세션 상태(기능 정의서 03절). IDLE은 열린 세션이 없는 차량 상태이고, 확인 완료(REVIEWED)는 서버의 상태다. */
enum class RentalState { IDLE, NOTICE, PICKUP, IN_RENTAL, RETURN_CHECK, RETURN, RETURNED, VOIDED }

sealed interface RentalEvent {
    data object StartPickup : RentalEvent
    data object AcceptNotice : RentalEvent
    data object DeclineNotice : RentalEvent
    data object PickupSaved : RentalEvent
    data object PickupFailed : RentalEvent
    data object StartReturnCheck : RentalEvent
    data object CancelReturnCheck : RentalEvent
    data object ConfirmReturn : RentalEvent
    data object ReturnSaved : RentalEvent
    data object ReturnFailed : RentalEvent
    data object Close : RentalEvent

    /** 직원만 할 수 있고 사유가 있어야 한다. */
    data class Void(val reason: String) : RentalEvent
}

/**
 * 전이표에 있는 이동만 허락하고, 허락하지 않는 이벤트면 null이다.
 * 정차 여부는 여기서 보지 않는다. 화면이 플랫폼의 주행 중 제한을 확인한 뒤에 이벤트를 보낸다.
 */
fun RentalState.next(event: RentalEvent): RentalState? = when (this) {
    RentalState.IDLE -> when (event) {
        RentalEvent.StartPickup -> RentalState.NOTICE
        else -> null
    }
    RentalState.NOTICE -> when (event) {
        RentalEvent.AcceptNotice -> RentalState.PICKUP
        RentalEvent.DeclineNotice -> RentalState.IDLE
        else -> null
    }
    RentalState.PICKUP -> when (event) {
        RentalEvent.PickupSaved -> RentalState.IN_RENTAL
        RentalEvent.PickupFailed -> RentalState.NOTICE
        else -> null
    }
    RentalState.IN_RENTAL -> when (event) {
        RentalEvent.StartReturnCheck -> RentalState.RETURN_CHECK
        is RentalEvent.Void -> RentalState.VOIDED.takeIf { event.reason.isNotBlank() }
        else -> null
    }
    RentalState.RETURN_CHECK -> when (event) {
        RentalEvent.CancelReturnCheck -> RentalState.IN_RENTAL
        RentalEvent.ConfirmReturn -> RentalState.RETURN
        is RentalEvent.Void -> RentalState.VOIDED.takeIf { event.reason.isNotBlank() }
        else -> null
    }
    RentalState.RETURN -> when (event) {
        RentalEvent.ReturnSaved -> RentalState.RETURNED
        RentalEvent.ReturnFailed -> RentalState.RETURN_CHECK
        else -> null
    }
    RentalState.RETURNED, RentalState.VOIDED -> when (event) {
        RentalEvent.Close -> RentalState.IDLE
        else -> null
    }
}

/**
 * 화면과 리포트에 보이는 짧은 기록 번호. "차량 4자리-월일-순번", 예: 0007-1105-B2.
 * 순번은 하루 234개(A1~Z9)까지다. 세션 자체는 UUID로 구분하고, 이 번호는 사람이 읽고 부르기 위한 것이다.
 */
object RecordNumber {
    const val PER_DAY = 26 * 9

    fun of(vehicleCode: String, date: LocalDate, sequence: Int): String {
        require(sequence in 0 until PER_DAY) { "하루 기록 번호는 ${PER_DAY}개까지입니다: $sequence" }
        val code = vehicleCode.takeLast(4).padStart(4, '0')
        return String.format(
            Locale.ROOT, "%s-%02d%02d-%c%d", code, date.monthValue, date.dayOfMonth, 'A' + sequence / 9, sequence % 9 + 1,
        )
    }
}
