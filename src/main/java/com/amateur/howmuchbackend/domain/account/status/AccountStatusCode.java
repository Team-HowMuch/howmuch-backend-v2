package com.amateur.howmuchbackend.domain.account.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AccountStatusCode implements StatusCode {
    ACCOUNT_NOT_FOUND("계좌를 찾을 수 없습니다.", 404),
    ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT("작성 중인 정산에서 수령 계좌로 사용 중입니다.", 409),
    INVALID_ACCOUNT_QUERY("계좌 조회 조건이 올바르지 않습니다.", 400),
    INVALID_ACCOUNT_DISPLAY_ORDER("계좌 표시 순서가 올바르지 않습니다.", 400),
    INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST("계좌 표시 순서 요청에는 활성 계좌 전체가 한 번씩 필요합니다.", 400),
    INVALID_ACCOUNT_TRANSACTION_SYNC_REQUEST("계좌 거래 동기화 요청 조건이 올바르지 않습니다.", 400),
    ACCOUNT_TRANSACTION_SYNC_JOB_NOT_FOUND("계좌 거래 동기화 작업을 찾을 수 없습니다.", 404),
    ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT("같은 멱등성 Key가 다른 동기화 요청에 사용되었습니다.", 409),
    ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING("해당 계좌의 거래 동기화 작업이 이미 진행 중입니다.", 409),
    ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED("해당 계좌에는 비동기 거래 동기화가 활성화되지 않았습니다.", 409),
    ACCOUNT_TRANSACTION_NOT_SUPPORTED("거래내역 조회를 지원하지 않는 계좌입니다.", 422),
    ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE("금융 거래 동기화 서버를 일시적으로 사용할 수 없습니다.", 503);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
