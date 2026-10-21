package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementMyCategoryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.*;

@Schema(requiredProperties = {"nettingId", "groupId", "version", "status", "openReviews", "creatorId", "creatorName", "createdAt",
        "completedAt", "supersededAt", "sources", "transfers"})
public record GroupNettingWebResponse(
        UUID nettingId,
        long groupId,
        long version,
        String status,
        int openReviews,
        @Schema(description = "이 압축 정산의 CREATE 실행을 요청한 사용자 ID. 현재 그룹장이나 조회자와 다를 수 있음")
        long creatorId,
        @Schema(description = "압축 실행자의 현재 이름") String creatorName,
        @Schema(description = "생성 시각. UTC 기준 offset 없는 값") LocalDateTime createdAt,
        @Schema(description = "완료 시각. UTC 기준 offset 없는 값. 미완료는 null", nullable = true)
        LocalDateTime completedAt,
        @Schema(description = "재압축으로 대체된 시각. UTC 기준 offset 없는 값. 미대체는 null", nullable = true)
        LocalDateTime supersededAt,
        List<Source> sources,
        List<Transfer> transfers) {
    @Schema(name = "GroupNettingSource", requiredProperties = {"settlementId", "title", "status",
            "requesterName", "requestedAt", "allocationType", "originalAmount", "actualDepositAmount",
            "replacedAmount", "storeName", "category", "participantCount", "payableParticipantCount",
            "completedParticipantCount"})
    public record Source(
            UUID settlementId,
            String title,
            String status,
            String requesterName,
            @Schema(description = "요청 시각. UTC 기준 offset 없는 값") LocalDateTime requestedAt,
            String allocationType,
            long originalAmount,
            long actualDepositAmount,
            long replacedAmount,
            @Schema(description = "결제 소스가 하나이고 영수증 OCR인 경우의 상호명. 그 외 null", nullable = true)
            String storeName,
            @Schema(description = "조회자 기준 카테고리. 개인 선택, 없으면 자동 분류. 없거나 유효하지 않으면 null", nullable = true)
            SettlementMyCategoryWebResponse category,
            @Schema(description = "원본의 전체 참여 인원(생성자 포함)") int participantCount,
            @Schema(description = "생성자를 제외한 Share 보유 납부 대상 인원. 확정된 더치페이의 0원 참여자는 제외")
            int payableParticipantCount,
            @Schema(description = "원본 실제 입금 완료 인원. 원본이 COMPLETED이면 상계 대체도 완료되어 납부 대상 전원. 부분 상계 입금은 원본별로 배분하지 않음")
            int completedParticipantCount) {}

    @Schema(name = "GroupNettingTransfer", requiredProperties = {"transferId", "payerId", "receiverId",
            "amount", "confirmedAmount", "reference", "organization", "maskedNumber", "payerName",
            "payerPublicId", "receiverName", "receiverPublicId", "status", "confirmedAt", "sourceSettlementIds", "reminderNextAvailableAt", "reminderBlockedReason"})
    public record Transfer(
            UUID transferId,
            long payerId,
            long receiverId,
            long amount,
            long confirmedAmount,
            String reference,
            String organization,
            String maskedNumber,
            String payerName,
            String payerPublicId,
            String receiverName,
            String receiverPublicId,
            @Schema(description = "확인액 0: UNPAID, 일부: PARTIAL, 전액: PAID")
            GroupNettingResult.TransferStatus status,
            @Schema(description = "마지막 자동 입금 귀속 또는 수동 검토 적용 시각. UTC 기준 offset 없는 값. 입금 전 null이며 부분 입금에도 제공", nullable = true)
            LocalDateTime confirmedAt,
            @Schema(description = "송금자 또는 수취인이 미납 채무 당사자인 관련 원본 정산 ID(선대 포함). 원본별 송금 금액 귀속이나 직접 채권 관계를 뜻하지 않음")
            List<UUID> sourceSettlementIds,
            @Schema(description = "다음 수동 독촉 가능 시각. UTC 기준 offset 없는 값. 최근 수동·자동 독촉에서 수동 쿨다운을 더한 시각이며, 기록이 없거나 이미 지났으면 null. null은 독촉 권한·상태를 보장하지 않음", nullable = true)
            LocalDateTime reminderNextAvailableAt,
            @Schema(description = "독촉 차단 사유. 비활성 압축 NETTING_NOT_ACTIVE → 입금 검토 중 DEPOSIT_REVIEW_OPEN → 일부/전액 입금 확인 DEPOSIT_RECEIVED 순으로 우선 적용. 해당 사유가 없으면 null이며 수취인 권한·쿨다운·압축 진행 제한은 별도 확인", nullable = true)
            GroupNettingResult.ReminderBlockedReason reminderBlockedReason) {}

    public static GroupNettingWebResponse from(GroupNettingResult r) {
        return new GroupNettingWebResponse(
                r.nettingId(),
                r.groupId(),
                r.version(),
                r.status(),
                r.openReviews(),
                r.creatorId(),
                r.creatorName(),
                r.createdAt(),
                r.completedAt(),
                r.supersededAt(),
                r.sources().stream()
                        .map(s -> new Source(
                                s.settlementId(), s.title(), s.status(), s.requesterName(),
                                s.requestedAt(), s.allocationType(), s.originalAmount(),
                                s.actualDepositAmount(), s.replacedAmount(), s.storeName(),
                                SettlementMyCategoryWebResponse.from(s.category()),
                                s.participantCount(), s.payableParticipantCount(), s.completedParticipantCount()))
                        .toList(),
                r.transfers().stream()
                        .map(t -> new Transfer(
                                t.transferId(), t.payerId(), t.receiverId(), t.amount(), t.confirmedAmount(),
                                t.reference(), t.organization(), t.maskedNumber(), t.payerName(),
                                t.payerPublicId(), t.receiverName(), t.receiverPublicId(),
                                t.status(), t.confirmedAt(), t.sourceSettlementIds(), t.reminderNextAvailableAt(), t.reminderBlockedReason()))
                        .toList());
    }
}
