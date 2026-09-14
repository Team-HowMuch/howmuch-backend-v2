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
                        .map(payment -> ParticipantPayment.from(
                                result.settlementId(), result.viewerRole(), payment
                        ))
                        .toList()
        );
    }

    @Schema(requiredProperties = {"amount", "storeName", "paidAt"})
    public record PaymentInfo(
            long amount,
            @Schema(nullable = true, description = "상호명. 영수증·결제내역 지출을 합쳐 한 건일 때만 채워진다.")
            String storeName,
            @Schema(nullable = true, description = "결제일(UTC, offset 없는 값). 영수증·결제내역 지출을 합쳐 한 건일 때만 채워지며 알 수 없으면 null이다.")
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
            "participantId", "displayName", "linked", "expectedAmount", "confirmedAmount", "paymentStatus", "avatarUrl"
    })
    public record ParticipantPayment(
            UUID participantId,
            String displayName,
            boolean linked,
            @Schema(nullable = true)
            Long expectedAmount,
            long confirmedAmount,
            SettlementDetailResult.PaymentStatus paymentStatus,
            @Schema(nullable = true, description = "인증이 필요한 프로필 이미지 상대 경로. 사진이 없거나 비회원이면 null")
            String avatarUrl
    ) {
        private static ParticipantPayment from(
                UUID settlementId,
                SettlementDetailResult.ViewerRole viewerRole,
                SettlementDetailResult.ParticipantPayment result
        ) {
            return new ParticipantPayment(
                    result.participantId(),
                    result.displayName(),
                    result.linked(),
                    result.expectedAmount(),
                    result.confirmedAmount(),
                    result.paymentStatus(),
                    result.avatarVersion() == null ? null
                            : viewerRole == SettlementDetailResult.ViewerRole.PARTICIPANT
                                    ? "/api/v2/user/me/avatar?v=" + result.avatarVersion()
                                    : "/api/v2/settlements/%s/participants/%s/avatar?v=%s".formatted(
                                            settlementId,
                                            result.participantId(),
                                            result.avatarVersion()
                                    )
            );
        }
    }
}
