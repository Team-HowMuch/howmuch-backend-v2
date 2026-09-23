package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void depositMatching_uniqueNameAndValidWonIncome_matched() {
        var payable = new SettlementDepositMatchingPolicy.PayableShare(
                SHARE_ID,
                "민선영",
                6_000,
                LocalDateTime.of(2026, 8, 24, 0, 0)
        );

        assertThat(policy.match(transaction("민 선 영 입금", "6000"), List.of(payable)))
                .contains(SHARE_ID);
        assertThat(policy.match(transaction("민선영 입금", "6000.5"), List.of(payable)))
                .isEmpty();
        assertThat(policy.match(transaction("민선영 입금", "7000"), List.of(payable)))
                .isEmpty();
    }

    @Test
    @DisplayName("전체 이름과 부분 이름 후보가 함께 있으면 전체 이름만 연결한다")
    void depositMatching_fullName_beatsSubstringMatch() {
        var shares = List.of(
                payable(SHARE_ID, "민선영", LocalDateTime.of(2026, 8, 24, 0, 0)),
                payable(UUID.randomUUID(), "선영", LocalDateTime.of(2026, 8, 24, 0, 0))
        );

        assertThat(policy.match(transaction("민선영 입금", "6000"), shares)).contains(SHARE_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "민선영, 폰토스뱅크 민선영 토스뱅크 계좌",
            "Alice Kim, 은행 Alice Kim 계좌",
            "Alice Kim, 은행 AliceKim 계좌",
            "민선영, 입금 민선영",
            "민선영, 민선영 입금 완료",
            "민선영, 민선영 입금 입금",
            "민선영, 민선영(입금)",
            "민선영, 민선영 최수아 입금",
            "민선영, 민선영/최수아"
    })
    @DisplayName("거래 설명에 전체 입금자명이 포함되면 연결한다")
    void depositMatching_fullNameInDescription_matched(String name, String description) {
        assertThat(policy.match(transaction(description, "6000"), List.of(
                payable(SHARE_ID, name, LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).contains(SHARE_ID);
    }

    @Test
    @DisplayName("거래 발생 후 시작된 정산 Share는 매칭 후보에서 제외한다")
    void depositMatching_settlementStartsAfterTransaction_excluded() {
        UUID laterShareId = UUID.randomUUID();
        var shares = List.of(
                payable(SHARE_ID, "민선영", LocalDateTime.of(2026, 8, 24, 0, 0)),
                payable(laterShareId, "민선영", LocalDateTime.of(2026, 8, 24, 2, 0))
        );

        assertThat(policy.match(transaction("민선영 입금", "6000"), shares))
                .contains(SHARE_ID);
    }

    @Test
    @DisplayName("UTC 스냅샷 이전 입금만 제외하고 동일 시각과 이후는 매칭한다")
    void depositMatching_atOrAfterSnapshot_matches() {
        var transaction = transaction("민선영 입금", "6000");
        var depositedAt = transaction.transactedAt();
        assertThat(policy.match(transaction, List.of(payable(SHARE_ID, "민선영", depositedAt.plusNanos(1)))))
                .isEmpty();
        assertThat(policy.match(transaction, List.of(payable(SHARE_ID, "민선영", depositedAt))))
                .contains(SHARE_ID);
        assertThat(policy.match(transaction, List.of(payable(SHARE_ID, "민선영", depositedAt.minusSeconds(1)))))
                .contains(SHARE_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "선영, 민선영 입금",
            "선영, 민선영",
            "민선, 민선영 입금",
            "선영, 민 선 영 입금",
            "민선영, 민선영입금",
            "민선영, 민선영2 입금",
            "Ann, Joanne 입금"
    })
    @DisplayName("부분 이름이나 문자·숫자에 붙은 이름으로 연결하지 않는다")
    void depositMatching_partialOrAttachedName_rejected(String name, String description) {
        assertThat(policy.match(transaction(description, "6000"), List.of(
                payable(SHARE_ID, name, LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "민선영, 민선영",
            "민선영, 민선영 입금",
            "민선영, 민 선 영 입금",
            "민 선 영, 민선영",
            "Alice Kim, ＡＬＩＣＥ ＫＩＭ 입금",
            "민선영, 민선영　입금",
            "민선영, 민선영 입금"
    })
    @DisplayName("전체 이름과 허용 접미어에 기존 공백·Unicode·대소문자 정규화를 적용한다")
    void depositMatching_normalizedFullName_matched(String name, String description) {
        assertThat(policy.match(transaction("  " + description + "  ", "3000"), List.of(
                payable(SHARE_ID, name, LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).contains(SHARE_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "　", " ", "\t\n"})
    @DisplayName("null과 정규화 후 빈 이름은 자동 연결하지 않는다")
    void depositMatching_missingOrBlankName_rejected(String name) {
        assertThat(policy.match(transaction("민선영 입금", "6000"), List.of(
                payable(SHARE_ID, name, LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "　", " ", "\t\n"})
    @DisplayName("null과 공백 거래 설명은 자동 연결하지 않는다")
    void rejectsMissingDescriptions(String description) {
        assertThat(policy.match(transaction(description, "6000"), List.of(
                payable(SHARE_ID, "민선영", LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "민선영, 민선영, 민선영 입금",
            "민선영, 민 선 영, 민선영 입금",
            "민선영, 민선영입금, 민선영 입금",
            "민선영, 최수아, 민선영 최수아 입금"
    })
    @DisplayName("동명이인·정규화 충돌·복수 해석과 복수 이름은 확정하지 않는다")
    void rejectsAmbiguousFullNames(String first, String second, String description) {
        var createdAt = LocalDateTime.of(2026, 8, 24, 0, 0);
        assertThat(policy.match(transaction(description, "6000"), List.of(
                payable(SHARE_ID, first, createdAt),
                payable(UUID.randomUUID(), second, createdAt)
        ))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "6000.5", "7000", "9223372036854775808"})
    @DisplayName("전체 이름이 일치해도 유효하지 않은 입금 금액은 거부한다")
    void rejectsInvalidAmounts(String amount) {
        assertThat(policy.match(transaction("민선영", amount), List.of(
                payable(SHARE_ID, "민선영", LocalDateTime.of(2026, 8, 24, 0, 0))
        ))).isEmpty();
    }

    private SettlementDepositMatchingPolicy.PayableShare payable(
            UUID shareId,
            String payerName,
            LocalDateTime snapshotCreatedAt
    ) {
        return new SettlementDepositMatchingPolicy.PayableShare(
                shareId,
                payerName,
                6_000,
                snapshotCreatedAt
        );
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
