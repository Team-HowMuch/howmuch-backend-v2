package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "title", "totalAmount", "allocationType", "status", "createdAt",
        "viewerRole", "creatorName", "bankName", "accountNumber", "institutionIconUrl",
        "paymentInfo", "paymentSummary",
        "reminderNextAvailableAt", "participants"
})
public record SettlementDetailWebResponse(
        UUID settlementId,
        String title,
        long totalAmount,
        @Schema(nullable = true)
        SettlementAllocationType allocationType,
        SettlementStatus status,
        LocalDateTime createdAt,
        SettlementDetailResult.ViewerRole viewerRole,
        String creatorName,
        @Schema(nullable = true)
        String bankName,
        @Schema(nullable = true)
        String accountNumber,
        @Schema(nullable = true)
        String institutionIconUrl,
        PaymentInfo paymentInfo,
        PaymentSummary paymentSummary,
        @Schema(
                nullable = true,
                description = "생성자 화면에서 관련 참여자의 독촉 쿨다운이 모두 끝나는 UTC 시각. "
                        + "offset 없는 값을 UTC로 해석하며 활성 쿨다운이 없으면 null"
        )
        LocalDateTime reminderNextAvailableAt,
        List<ParticipantPayment> participants
) {
    public static SettlementDetailWebResponse from(SettlementDetailResult result) {
        return new SettlementDetailWebResponse(
                result.settlementId(),
                result.title(),
                result.totalAmount(),
                result.allocationType(),
                result.status(),
                result.createdAt(),
                result.viewerRole(),
                result.creatorName(),
                result.bankName(),
                result.accountNumber(),
                result.institutionIconUrl(),
                PaymentInfo.from(result.paymentInfo()),
                PaymentSummary.from(result.paymentSummary()),
                result.reminderNextAvailableAt(),
                result.participants().stream()
                        .map(ParticipantPayment::from)
                        .toList()
        );
    }

    @Schema(requiredProperties = {"amount", "storeName", "paidAt"})
    public record PaymentInfo(
            long amount,
            @Schema(nullable = true)
            String storeName,
            @Schema(nullable = true)
            LocalDateTime paidAt
    ) {
        private static PaymentInfo from(SettlementDetailResult.PaymentInfo result) {
            return new PaymentInfo(result.amount(), result.storeName(), result.paidAt());
        }
    }

    @Schema(requiredProperties = {
            "expectedAmount", "confirmedAmount", "unpaidAmount", "progressPercent",
            "participantCount", "payableParticipantCount", "completedParticipantCount"
    })
    public record PaymentSummary(
            long expectedAmount,
            long confirmedAmount,
            long unpaidAmount,
            int progressPercent,
            @Schema(description = "납부 대상 인원. 상세에서는 payableParticipantCount와 같은 값이다.")
            int participantCount,
            @Schema(
                    description = "납부 대상 인원. 돈을 받는 생성자를 제외한다. "
                            + "목록 API와 이름이 같으므로 진행률의 분모로는 이 값을 쓴다."
            )
            int payableParticipantCount,
            int completedParticipantCount
    ) {
        private static PaymentSummary from(SettlementDetailResult.PaymentSummary result) {
            return new PaymentSummary(
                    result.expectedAmount(),
                    result.confirmedAmount(),
                    result.unpaidAmount(),
                    result.progressPercent(),
                    result.participantCount(),
                    result.payableParticipantCount(),
                    result.completedParticipantCount()
            );
        }
    }

    @Schema(requiredProperties = {
            "participantId", "displayName", "linked", "expectedAmount", "confirmedAmount", "paymentStatus"
    })
    public record ParticipantPayment(
            UUID participantId,
            String displayName,
            boolean linked,
            @Schema(nullable = true)
            Long expectedAmount,
            long confirmedAmount,
            SettlementDetailResult.PaymentStatus paymentStatus
    ) {
        private static ParticipantPayment from(
                SettlementDetailResult.ParticipantPayment result
        ) {
            return new ParticipantPayment(
                    result.participantId(),
                    result.displayName(),
                    result.linked(),
                    result.expectedAmount(),
                    result.confirmedAmount(),
                    result.paymentStatus()
            );
        }
    }
}
