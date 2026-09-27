package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 삭제 가능 정책")
class SettlementDeletionPolicyTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 18, 0);

    private final SettlementDeletionPolicy policy = new SettlementDeletionPolicy();

    @Test
    @DisplayName("DRAFT·REQUESTED는 Share가 없을 때만 삭제를 허용한다")
    void settlementDeletion_beforeTransferWithoutShare_allowed() {
        policy.validate(settlement(SettlementStatus.DRAFT), false);
        policy.validate(settlement(SettlementStatus.REQUESTED), false);
    }

    @Test
    @DisplayName("SUSPENDED·COMPLETED는 Share 유무에 관계없이 삭제를 허용한다")
    void settlementDeletion_suspendedAndCompleted_allowed() {
        for (SettlementStatus status : new SettlementStatus[]{SettlementStatus.SUSPENDED, SettlementStatus.COMPLETED}) {
            policy.validate(settlement(status), false);
            policy.validate(settlement(status), true);
        }
    }

    @Test
    @DisplayName("Share가 있는 DRAFT·REQUESTED와 AWAITING_DEPOSIT은 삭제를 차단한다")
    void settlementDeletion_afterTransferOrDeposit_rejected() {
        assertThatThrownBy(() -> policy.validate(settlement(SettlementStatus.DRAFT), true))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE));
        assertThatThrownBy(() -> policy.validate(
                settlement(SettlementStatus.REQUESTED),
                true
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE));

        assertThatThrownBy(() -> policy.validate(
                settlement(SettlementStatus.AWAITING_DEPOSIT),
                true
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE));
        assertThatThrownBy(() -> policy.validate(settlement(SettlementStatus.AWAITING_DEPOSIT), false))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE));
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
                "삭제 정책 테스트",
                10_000,
                status,
                NOW,
                NOW
        );
    }
}
