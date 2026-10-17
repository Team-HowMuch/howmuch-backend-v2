package com.amateur.howmuchbackend.domain.settlement.recurring;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RecurringSettlementStatusCode implements StatusCode {
    INVALID_RECURRENCE_SCHEDULE("반복 주기와 첫 정산일을 확인해주세요.", 400),
    INVALID_RECURRING_SETTLEMENT_SOURCE("그룹의 수동 더치페이 초안만 반복 등록할 수 있습니다.", 400),
    INVALID_RECURRING_SETTLEMENT_CATEGORY("선택할 수 없는 정산 카테고리입니다.", 400),
    INVALID_RECURRING_SETTLEMENT_CURSOR("목록 조회 조건이 올바르지 않습니다.", 400),
    RECURRING_SETTLEMENT_NOT_FOUND("반복 정산 설정을 찾을 수 없습니다.", 404),
    RECURRING_SETTLEMENT_ACCESS_DENIED("반복 정산에 접근할 권한이 없습니다.", 403),
    RECURRING_SETTLEMENT_SOURCE_ALREADY_USED("다른 조건으로 이미 반복 등록한 초안입니다.", 409),
    RECURRING_SETTLEMENT_EXECUTION_NOT_DUE("현재 발행할 수 있는 예정 회차가 아닙니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
