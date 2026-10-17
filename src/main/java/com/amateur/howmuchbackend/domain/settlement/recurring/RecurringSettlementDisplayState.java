package com.amateur.howmuchbackend.domain.settlement.recurring;

/**
 * 목록·홈 카드에 표시할 반복 설정 상태. 발행이 지연되거나 실패해도 실제 예정 회차는 그대로 두고,
 * 화면에만 "처리 지연/확인 필요"를 구분해 보여주기 위한 파생 값이다.
 */
public enum RecurringSettlementDisplayState {
    /** 해지됨. */
    CANCELLED,
    /** 자동 시도 소진 또는 비일시 오류로 운영자 확인 필요. */
    REVIEW,
    /** 일시 오류 후 자동 재시도 대기 중. */
    RETRYING,
    /** 예정일이 지났지만 아직 발행되지 않음. */
    OVERDUE,
    /** 오늘이 예정일(D-day 0). */
    TODAY,
    /** 아직 도래하지 않음. */
    SCHEDULED
}
