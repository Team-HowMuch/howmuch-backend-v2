package com.amateur.howmuchbackend.domain.settlement.netting;

import com.amateur.howmuchbackend.common.exception.StatusCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NettingStatusCode implements StatusCode {
    NETTING_ESTIMATE_BUSY("예상 송금 건수를 계산하고 있습니다. 잠시 후 다시 조회해주세요.", 503),
    NETTING_ESTIMATE_UNAVAILABLE("예상 송금 건수를 잠시 조회할 수 없습니다.", 503),
    NETTING_PROCESSING("그룹 정산을 압축하고 있습니다. 잠시 후 다시 시도해주세요.", 409),
    NETTING_NOT_FOUND("압축 정산을 찾을 수 없습니다.", 404),
    NETTING_ACCESS_DENIED("압축 정산에 접근할 권한이 없습니다.", 403),
    NETTING_INPUT_CHANGED("정산 또는 계좌 정보가 변경되었습니다. 다시 확인해주세요.", 409),
    NETTING_ALREADY_ACTIVE("진행 중인 압축 작업 또는 미해결 입금 검토가 있습니다.", 409),
    NETTING_INSUFFICIENT_SETTLEMENTS("압축할 정산이 2건 이상 있어야 합니다.", 409),
    NETTING_NO_ELIGIBLE_DEBTS("압축할 수 있는 그룹 정산이 없습니다.", 409),
    NETTING_RECEIVING_ACCOUNT_REQUIRED("수취 멤버의 기본 원화 입출금 계좌 연결이 필요합니다.", 422),
    NETTING_CALCULATOR_UNAVAILABLE("현재 정산을 처리할 계산기를 사용할 수 없습니다.", 503),
    NETTING_CALCULATION_INVALID("압축 정산 계산 결과를 검증할 수 없습니다.", 500),
    NETTING_INVALID_SOURCE("그룹 정산 데이터 또는 멤버 상태를 확인해주세요.", 409),
    NETTING_IDEMPOTENCY_CONFLICT("같은 요청 키가 다른 작업에 사용되었습니다.", 409),
    NETTING_EXECUTION_EXPIRED("실행 시간이 초과되었습니다. 다시 확인해주세요.", 409),
    NETTING_EXECUTION_FENCED("다른 작업자가 실행을 복구했습니다.", 409),
    NETTING_REVIEW_REQUIRED("자동으로 확인할 수 없는 입금이 있어 검토가 필요합니다.", 409),
    NETTING_INVALID_REVIEW("실제 입금 내역과 처리 근거를 확인해주세요.", 422),
    NETTING_TRANSFER_REPLACED("압축 정산의 최종 송금 안내를 확인해주세요.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
