package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "title", "totalAmount", "allocationType", "status", "createdAt", "endedAt",
        "viewerRole", "creatorName", "bankName", "accountNumber", "institutionIconUrl",
        "paymentInfo", "paymentSummary",
        "reminderNextAvailableAt", "participants", "items", "myCategory"
})
public record SettlementDetailWebResponse(
        UUID settlementId,
        String title,
        long totalAmount,
        @Schema(nullable = true)
        SettlementAllocationType allocationType,
        @io.swagger.v3.oas.annotations.media.Schema(
                        description =
                                "압축 정산에 포함된 원본은 NETTING_IN_PROGRESS, 최종 정산 완료 후에는 COMPLETED입니다.")
                SettlementStatus status,
        LocalDateTime createdAt,
        @Schema(nullable = true, description = "공통 종료 시각. COMPLETED는 완료 시각, SUSPENDED는 현재 중단 시각이며 "
                + "그 외 상태는 null입니다. UTC 기준 offset 없는 값입니다.")
        LocalDateTime endedAt,
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
        List<ParticipantPayment> participants,
        List<SettlementItemWebResponse> items,
        @Schema(nullable = true, description =
                                "조회하는 회원 기준 카테고리. 회원이 고른 값, 없으면 자동 분류 값이다. 정산 요청 전, 분류 전, 기능 배포 전에"
                                    + " 요청된 정산은 null이다. 미분류면 code가 UNCATEGORIZED다.")
        SettlementMyCategoryWebResponse myCategory
) {
    public static SettlementDetailWebResponse from(SettlementDetailResult result) {
        return new SettlementDetailWebResponse(
                result.settlementId(),
                result.title(),
                result.totalAmount(),
                result.allocationType(),
                result.status(),
                result.createdAt(),
                result.endedAt(),
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
                        .toList(),
                result.items().stream().map(SettlementItemWebResponse::from).toList(),
                SettlementMyCategoryWebResponse.from(result.myCategory())
        );
    }

    @Schema(requiredProperties = {"amount", "storeName", "paidAt", "discount", "deliveryFee"})
    public record PaymentInfo(
            long amount,
            @Schema(nullable = true, description =
                                    "상호명. 영수증·결제내역 지출을 합쳐 한 건이고 그 지출이 영수증일 때만 채워진다. 결제내역은 상호명을 알 수"
                                        + " 없어 null이다.")
            String storeName,
            @Schema(nullable = true, description =
                                    "결제일(UTC, offset 없는 값). 영수증·결제내역 지출을 합쳐 한 건일 때만 채워지며 알 수 없으면"
                                        + " null이다.")
            LocalDateTime paidAt,
            long discount,
            @Schema(description = "영수증 전체 순 배달비 합계", minimum = "0")
            long deliveryFee
    ) {
        private static PaymentInfo from(SettlementDetailResult.PaymentInfo result) {
            return new PaymentInfo(result.amount(), result.storeName(), result.paidAt(),
                    result.discount(), result.deliveryFee());
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
                    description = "납부 대상 인원. 생성자는 제외하며, 확정된 더치페이는 0원 참여자도 제외한다. "
                            + "목록 API와 이름이 같으므로 진행률의 분모로는 이 값을 쓴다."
            )
            int payableParticipantCount,
            @Schema(description = "납부 대상 중 입금이 완료된 인원. 확정된 더치페이의 0원 참여자는 제외한다.")
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
            "participantId", "displayName", "linked", "expectedAmount", "confirmedAmount", "paymentStatus", "submitted", "avatarUrl", "discountShare", "deliveryFeeShare"
    })
    public record ParticipantPayment(
            UUID participantId,
            String displayName,
            boolean linked,
            @Schema(nullable = true, description = "확정된 개인 금액. 확정된 더치페이의 0원 참여자는 0이며, 미확정이면 null이다.")
            Long expectedAmount,
            long confirmedAmount,
            @Schema(description = "PAID는 납부가 끝났거나 확정 금액이 0원이라 추가 납부가 없음을 뜻한다.")
            SettlementDetailResult.PaymentStatus paymentStatus,
            @Schema(description = "품목 선택 제출 기록이 있으면 true. 납부 상태 및 Share 계산 여부와 무관하다.")
            boolean submitted,
            @Schema(nullable = true, description = "인증이 필요한 프로필 이미지 상대 경로. 사진이 없거나 비회원이면 null")
            String avatarUrl,
            long discountShare,
            @Schema(description = "품목 순액 비율로 배분한 배달비. 전체 제출 전 잠정치이며 배분 확정 후 원 단위 잔액을 포함합니다.", minimum = "0")
            long deliveryFeeShare
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
                    result.submitted(),
                    result.avatarVersion() == null ? null
                            : viewerRole == SettlementDetailResult.ViewerRole.PARTICIPANT
                                    ? "/api/v2/user/me/avatar?v=" + result.avatarVersion()
                                    : "/api/v2/settlements/%s/participants/%s/avatar?v=%s".formatted(
                                            settlementId,
                                            result.participantId(),
                                            result.avatarVersion()
                                    ),
                    result.discountShare(),
                    result.deliveryFeeShare()
            );
        }
    }
}
