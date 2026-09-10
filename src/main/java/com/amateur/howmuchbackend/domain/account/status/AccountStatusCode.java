package com.amateur.howmuchbackend.domain.account.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AccountStatusCode implements StatusCode {
    ACCOUNT_NOT_FOUND("계좌를 찾을 수 없습니다.", 404),
    ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT("작성 중인 정산에서 수령 계좌로 사용 중입니다.", 409),
    ACCOUNT_IN_USE_BY_SETTLEMENT("진행 중인 정산에서 수령 계좌로 사용 중입니다.", 409),
    INVALID_ACCOUNT_QUERY("계좌 조회 조건이 올바르지 않습니다.", 400),
    INVALID_ACCOUNT_DISPLAY_ORDER("계좌 표시 순서가 올바르지 않습니다.", 400),
    INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST("계좌 표시 순서 요청에는 활성 계좌 전체가 한 번씩 필요합니다.", 400),
    ACCOUNT_TRANSACTION_NOT_SUPPORTED("거래내역 조회를 지원하지 않는 계좌입니다.", 422);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
