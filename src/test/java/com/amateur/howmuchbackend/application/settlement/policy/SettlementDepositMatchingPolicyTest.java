package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정산 입금 자동 대조 Policy")
class SettlementDepositMatchingPolicyTest {
    private static final UUID SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");
    private final SettlementDepositMatchingPolicy policy = new SettlementDepositMatchingPolicy();

    @Test
    @DisplayName("이름이 유일하고 정수 원 금액이 남은 금액 이하인 수입 거래만 연결한다")
    void matchesOnlyUnambiguousWonIncome() {
        var payable = new SettlementDepositMatchingPolicy.PayableShare(
                SHARE_ID,
                "민선영",
                6_000
        );

        assertThat(policy.match(transaction("민 선 영 입금", "6000"), List.of(payable)))
                .contains(SHARE_ID);
        assertThat(policy.match(transaction("민선영 입금", "6000.5"), List.of(payable)))
                .isEmpty();
        assertThat(policy.match(transaction("민선영 입금", "7000"), List.of(payable)))
                .isEmpty();
    }

    @Test
    @DisplayName("거래 설명이 둘 이상의 참여자 이름과 일치하면 자동 연결하지 않는다")
    void rejectsAmbiguousNames() {
        var shares = List.of(
                new SettlementDepositMatchingPolicy.PayableShare(SHARE_ID, "민선영", 6_000),
                new SettlementDepositMatchingPolicy.PayableShare(UUID.randomUUID(), "선영", 6_000)
        );

        assertThat(policy.match(transaction("민선영 입금", "6000"), shares)).isEmpty();
    }

    private Transaction transaction(String description, String amount) {
        return new Transaction(
                1L,
                7L,
                "a".repeat(64),
                AccountTransactionType.INCOME,
                new BigDecimal(amount),
                LocalDateTime.of(2026, 8, 24, 1, 0),
                description,
                new BigDecimal("100000")
        );
    }
}
