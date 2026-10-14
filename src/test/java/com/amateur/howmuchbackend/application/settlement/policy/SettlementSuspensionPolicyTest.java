package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 수동 중단 정책")
class SettlementSuspensionPolicyTest {
    private static final Long CREATOR_ID = 11L;
    private static final Long PAYER_ID = 22L;
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 1, 0);

    private final SettlementSuspensionPolicy policy = new SettlementSuspensionPolicy();
    private final SettlementParticipant creator = SettlementParticipant.registeredUser(
            SETTLEMENT_ID, CREATOR_ID, "생성자", 0, NOW
    );
    private final SettlementParticipant payer = SettlementParticipant.registeredUser(
            SETTLEMENT_ID, PAYER_ID, "민선영", 1, NOW
    );

    @Test
    @DisplayName("송금 가능한 Share나 다른 참여자 제출이 없는 REQUESTED는 은행 조회를 생략한다")
    void requestedWithoutTransferCapability_doesNotRefresh() {
        assertThat(policy.requiresDepositRefresh(
                settlement(SettlementStatus.REQUESTED),
                List.of(),
                List.of(creator, payer),
                List.of(SettlementItemSubmission.submit(SETTLEMENT_ID, creator.id(), NOW))
        )).isFalse();
    }

    @Test
    @DisplayName("다른 참여자 제출 기록만 있어도 재조회 대상으로 삼는다")
    void requestedWithOtherParticipantSubmission_refreshes() {
        assertThat(policy.requiresDepositRefresh(
                settlement(SettlementStatus.REQUESTED),
                List.of(),
                List.of(creator, payer),
                List.of(SettlementItemSubmission.submit(SETTLEMENT_ID, payer.id(), NOW))
        )).isTrue();
    }

    @Test
    @DisplayName("요청 상태에서도 송금 Share가 있으면 재조회한다")
    void requestedWithShare_refreshes() {
        assertThat(policy.requiresDepositRefresh(
                settlement(SettlementStatus.REQUESTED),
                List.of(SettlementShare.create(SETTLEMENT_ID, payer.id(), 1000, NOW)),
                List.of(creator, payer),
                List.of()
        )).isTrue();
    }

    @Test
    @DisplayName("AWAITING_DEPOSIT는 모든 제출 완료 후에도 재조회한다")
    void awaitingDepositAlwaysRefreshes() {
        assertThat(policy.requiresDepositRefresh(
                settlement(SettlementStatus.AWAITING_DEPOSIT),
                List.of(),
                List.of(creator),
                List.of()
        )).isTrue();
    }

    @Test
    @DisplayName("기록된 부분 입금 또는 미귀속 양수 입금은 중단을 거절한다")
    void anyKnownOrAmbiguousPayment_rejects() {
        assertNotSuspendable(true, false);
        assertNotSuspendable(false, true);
    }

    @Test
    @DisplayName("입금 기록과 미귀속 양수 입금이 모두 없으면 중단을 허용한다")
    void noRecordedOrAmbiguousPayment_allows() {
        policy.validateNoPayments(false, false);
    }

    private void assertNotSuspendable(boolean hasDeposit, boolean hasUnmatchedIncome) {
        assertThatThrownBy(() -> policy.validateNoPayments(hasDeposit, hasUnmatchedIncome))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "공동 정산",
                1000,
                SettlementAllocationType.ITEM_ALLOCATION,
                null,
                status,
                NOW,
                NOW
        );
    }
}
