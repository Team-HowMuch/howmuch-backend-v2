package com.amateur.howmuchbackend.domain.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("계좌 도메인 정책")
class AccountTest {
    @Test
    @DisplayName("활성 원화 입출금 계좌를 송금 수령 가능한 계좌 사실로 분류한다")
    void identifiesActiveWonDemandDeposit() {
        Account account = account(true, AccountType.DEPOSIT_TRUST, "11", "KRW");

        assertThat(account.isActiveWonDemandDeposit()).isTrue();
    }

    @Test
    @DisplayName("비활성·외화·비입출금 계좌를 원화 입출금 계좌로 분류하지 않는다")
    void rejectsAccountsWithoutDemandDepositFacts() {
        List<Account> unsupported = List.of(
                account(false, AccountType.DEPOSIT_TRUST, "11", "KRW"),
                account(true, AccountType.FOREIGN_CURRENCY, "11", "KRW"),
                account(true, AccountType.DEPOSIT_TRUST, "12", "KRW"),
                account(true, AccountType.DEPOSIT_TRUST, "11", "USD"),
                account(true, AccountType.DEPOSIT_TRUST, "11", null)
        );

        assertThat(unsupported).allMatch(account -> !account.isActiveWonDemandDeposit());
    }

    private Account account(
            boolean active,
            AccountType type,
            String productCode,
            String currency
    ) {
        return new Account(
                1L,
                1L,
                1L,
                "0020",
                "candidate-1",
                "1002123456789",
                type,
                "생활비 통장",
                "****6789",
                new BigDecimal("1000000"),
                currency,
                active,
                productCode,
                true,
                0
        );
    }
}
