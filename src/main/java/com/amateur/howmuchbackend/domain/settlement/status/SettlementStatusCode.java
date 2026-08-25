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
    INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE("정산 지출 원본 참조가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_PAYMENT_IMPORT_REQUEST("가져올 계좌와 결제내역 ID가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_TOTAL("정산 지출 누적액이 허용 범위를 초과했습니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_ORDER("정산 지출 표시 순서는 0 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_EXPENSE_UPDATE("수정할 정산 지출 설명 또는 금액이 필요합니다.", 400),
    INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST("가져올 OCR 영수증 참조가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_ITEM_NAME("정산 품목 이름은 1자 이상 500자 이하여야 합니다.", 400),
    INVALID_SETTLEMENT_ITEM_QUANTITY("정산 품목 수량은 1개 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_ITEM_AMOUNT("정산 품목 금액은 1원 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_ITEM_ORDER("정산 품목 표시 순서는 0 이상이어야 합니다.", 400),
    INVALID_SETTLEMENT_ITEM_SELECTION("정산 품목 선택 정보가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_ITEM_ALLOCATION_FRACTION("품목 선택 비율이 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_ALLOCATION_TYPE("정산 배분 유형이 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_RECEIVING_ACCOUNT_ID("정산 수령 계좌 ID는 양수여야 합니다.", 400),
    INVALID_SETTLEMENT_RECEIVING_ACCOUNT_SNAPSHOT("정산 수령 계좌 정보가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_LINK_TOKEN_HASH("정산 링크 토큰 hash가 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_LINK_EXPIRATION("정산 링크 만료 시각이 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_PARTICIPANT_CONTEXT("정산 링크 참여 문맥이 올바르지 않습니다.", 400),
    INVALID_SETTLEMENT_SHARE_AMOUNT("정산 Share 금액은 1원 이상이어야 합니다.", 400),
    INVALID_DUTCH_PAY_AMOUNT("더치페이 참여자 금액은 0원 이상이어야 합니다.", 400),
    SETTLEMENT_NOT_FOUND("정산을 찾을 수 없습니다.", 404),
    SETTLEMENT_EXPENSE_NOT_FOUND("정산 지출을 찾을 수 없습니다.", 404),
    SETTLEMENT_ITEM_NOT_FOUND("정산 품목을 찾을 수 없습니다.", 404),
    SETTLEMENT_PAYMENT_TRANSACTION_NOT_FOUND("가져올 결제내역을 찾을 수 없습니다.", 404),
    SETTLEMENT_RECEIPT_EXTRACTION_NOT_FOUND("OCR 영수증 결과를 찾을 수 없습니다.", 404),
    SETTLEMENT_PARTICIPANT_NOT_FOUND("정산 참여자를 찾을 수 없습니다.", 404),
    SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND("추가할 정산 참여자 후보를 찾을 수 없습니다.", 404),
    SETTLEMENT_LINK_NOT_FOUND("유효한 정산 링크를 찾을 수 없습니다.", 404),
    SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND("유효한 정산 링크 참여 문맥을 찾을 수 없습니다.", 404),
    SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND("송금 안내를 찾을 수 없습니다.", 404),
    SETTLEMENT_DEPOSIT_DATA_NOT_FOUND("정산 입금 확인 데이터를 찾을 수 없습니다.", 404),
    SETTLEMENT_ACCESS_DENIED("해당 정산에 접근할 권한이 없습니다.", 403),
    SETTLEMENT_GROUP_ACCESS_DENIED("해당 그룹 구성원을 조회할 권한이 없습니다.", 403),
    SETTLEMENT_NOT_EDITABLE("초안 상태의 정산만 수정할 수 있습니다.", 409),
    SETTLEMENT_NOT_DELETABLE("송금 또는 입금이 진행된 정산은 삭제할 수 없습니다.", 409),
    SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE("입금 대기 또는 완료 상태의 정산만 입금 현황을 확인할 수 있습니다.", 409),
    SETTLEMENT_PARTICIPANT_ALREADY_EXISTS("이미 정산에 포함된 참여자입니다.", 409),
    SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED("정산 생성자는 참여자에서 제거할 수 없습니다.", 409),
    SETTLEMENT_EXPENSE_NOT_EDITABLE("수동 등록한 정산 지출만 수정할 수 있습니다.", 409),
    SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE("출금 내역의 정수 원 금액만 정산 지출로 가져올 수 있습니다.", 409),
    SETTLEMENT_EXPENSE_ALREADY_IMPORTED("이미 이 정산에 가져온 결제내역입니다.", 409),
    SETTLEMENT_RECEIPT_ALREADY_IMPORTED("이미 이 정산에 가져온 OCR 영수증입니다.", 409),
    SETTLEMENT_RECEIPT_EXTRACTION_INVALID("OCR 영수증 총액과 품목 합계가 올바르지 않습니다.", 409),
    SETTLEMENT_ALLOCATION_NOT_SELECTED("정산 배분 유형을 먼저 선택해야 합니다.", 409),
    SETTLEMENT_DUTCH_PAY_REQUIRED("더치페이 유형의 정산만 금액을 관리할 수 있습니다.", 409),
    SETTLEMENT_PARTICIPANTS_INSUFFICIENT("더치페이는 생성자를 포함해 참여자가 2명 이상이어야 합니다.", 409),
    SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND("정산 생성자 참여자를 찾을 수 없습니다.", 409),
    SETTLEMENT_REQUEST_PARTICIPANTS_INSUFFICIENT("정산 요청에는 생성자를 포함해 참여자가 2명 이상 필요합니다.", 409),
    SETTLEMENT_EXPENSE_REQUIRED("배분할 정산 지출이 필요합니다.", 409),
    SETTLEMENT_EXPENSE_TOTAL_MISMATCH("정산 기준 총액과 지출 합계가 일치하지 않습니다.", 409),
    SETTLEMENT_ITEM_ALLOCATION_NOT_AVAILABLE("OCR 품목 저장 기능이 준비된 후 품목별 할당을 선택할 수 있습니다.", 409),
    SETTLEMENT_ITEM_REQUIRED("품목별 할당을 선택하려면 OCR 품목이 필요합니다.", 409),
    SETTLEMENT_ITEM_TOTAL_MISMATCH("OCR 품목 합계와 전체 지출 합계가 일치하지 않습니다.", 409),
    SETTLEMENT_ITEM_SELECTION_NOT_FOUND("정산 품목 선택을 찾을 수 없습니다.", 404),
    SETTLEMENT_ITEM_ALLOCATION_AMOUNT_NOT_EXACT("품목 금액을 선택한 비율로 정수 원 단위로 나눌 수 없습니다.", 409),
    SETTLEMENT_ITEM_ALLOCATION_REQUIRED("품목별 할당 정산에서만 품목을 선택할 수 있습니다.", 409),
    SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE("제출 전 요청 상태에서만 품목 선택을 변경할 수 있습니다.", 409),
    SETTLEMENT_ITEM_OVER_ALLOCATED("품목 선택 비율 합은 1을 초과할 수 없습니다.", 409),
    SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS("이미 품목 선택을 제출했습니다.", 409),
    SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED("송금 수령을 지원하는 원화 입출금 계좌만 선택할 수 있습니다.", 422),
    SETTLEMENT_REQUEST_RECEIVING_ACCOUNT_REQUIRED("정산 요청 전에 수령 계좌를 선택해야 합니다.", 409),
    SETTLEMENT_SHARE_AMOUNT_REQUIRED("모든 정산 Share 금액은 1원 이상이어야 합니다.", 409),
    SETTLEMENT_SHARE_TOTAL_MISMATCH("정산 Share 합계가 정산 총액과 일치하지 않습니다.", 409),
    SETTLEMENT_DUTCH_PAY_ALLOCATION_NOT_FOUND("더치페이 참여자 배분을 찾을 수 없습니다.", 404),
    SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE("더치페이 참여자 배분이 완전하지 않습니다.", 409),
    INVALID_DUTCH_PAY_TOTAL("더치페이 배분 합계가 허용 범위를 초과했습니다.", 400),
    INVALID_SETTLEMENT_STATUS_TRANSITION("허용되지 않는 정산 상태 변경입니다.", 409),
    SETTLEMENT_RECEIPT_EXTRACTION_UNAVAILABLE("운영 OCR 연동이 아직 준비되지 않았습니다.", 503);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
