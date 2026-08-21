package com.amateur.howmuchbackend.domain.settlement.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SettlementStatusCode implements StatusCode {
    INVALID_SETTLEMENT_TITLE("정산 제목은 1자 이상 100자 이하여야 합니다.", 400),
    INVALID_SETTLEMENT_AMOUNT("정산 총액은 1원 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_UPDATE("수정할 제목 또는 총액이 필요합니다.", 400),
    SETTLEMENT_NOT_FOUND("정산을 찾을 수 없습니다.", 404),
    SETTLEMENT_ACCESS_DENIED("해당 정산에 접근할 권한이 없습니다.", 403),
    SETTLEMENT_NOT_EDITABLE("초안 상태의 정산만 수정할 수 있습니다.", 409),
    INVALID_SETTLEMENT_STATUS_TRANSITION("허용되지 않는 정산 상태 변경입니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
