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
    INVALID_SETTLEMENT_PARTICIPANT_IDENTITY("정산 참여자 식별 정보가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME("정산 참여자 이름은 1자 이상 100자 이하여야 합니다.", 400),
    INVALID_SETTLEMENT_PARTICIPANT_ORDER("정산 참여자 표시 순서는 0 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_PARTICIPANT_ADD_REQUEST("참여자 선택 경로에 맞는 식별 정보가 필요합니다.", 400),
    INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST("연락처 매칭 요청이 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_GROUP_ID("그룹 ID는 양수여야 합니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_DESCRIPTION("정산 지출 설명은 1자 이상 500자 이하여야 합니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_AMOUNT("정산 지출 금액은 1원 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_TOTAL("정산 지출 누적액이 허용 범위를 초과했습니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_ORDER("정산 지출 표시 순서는 0 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_UPDATE("수정할 정산 지출 설명 또는 금액이 필요합니다.", 400),
    SETTLEMENT_NOT_FOUND("정산을 찾을 수 없습니다.", 404),
    SETTLEMENT_EXPENSE_NOT_FOUND("정산 지출을 찾을 수 없습니다.", 404),
    SETTLEMENT_PARTICIPANT_NOT_FOUND("정산 참여자를 찾을 수 없습니다.", 404),
    SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND("추가할 정산 참여자 후보를 찾을 수 없습니다.", 404),
    SETTLEMENT_ACCESS_DENIED("해당 정산에 접근할 권한이 없습니다.", 403),
    SETTLEMENT_GROUP_ACCESS_DENIED("해당 그룹 구성원을 조회할 권한이 없습니다.", 403),
    SETTLEMENT_NOT_EDITABLE("초안 상태의 정산만 수정할 수 있습니다.", 409),
    SETTLEMENT_PARTICIPANT_ALREADY_EXISTS("이미 정산에 포함된 참여자입니다.", 409),
    SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED("정산 생성자는 참여자에서 제거할 수 없습니다.", 409),
    SETTLEMENT_EXPENSE_NOT_EDITABLE("수동 등록한 정산 지출만 수정하거나 삭제할 수 있습니다.", 409),
    INVALID_SETTLEMENT_STATUS_TRANSITION("허용되지 않는 정산 상태 변경입니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
