package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정산 상세·참여 링크의 공통 결제 표시 규칙")
class SettlementPaymentInfoTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 31, 15, 5);

    @ParameterizedTest(name = "{0}")
    @MethodSource("paymentInfoCases")
    void paymentInfo_multipleEligibleExpenses_resolvesOnlyOne(
            String scenario,
            List<SettlementExpense> expenses,
            String expectedStoreName,
            LocalDateTime expectedPaidAt
    ) {
        assertThat(SettlementPaymentInfo.resolve(expenses))
                .isEqualTo(new SettlementPaymentInfo(expectedStoreName, expectedPaidAt));
    }

    private static Stream<Arguments> paymentInfoCases() {
        SettlementExpense payment = SettlementExpense.paymentHistory(
                SETTLEMENT_ID, 1L, "transaction-1", "행복식당", 10_000, PAID_AT, 0, NOW
        );
        SettlementExpense anotherPayment = SettlementExpense.paymentHistory(
                SETTLEMENT_ID, 1L, "transaction-2", "카페", 5_000, PAID_AT, 1, NOW
        );
        SettlementExpense receipt = SettlementExpense.receiptOcr(
                SETTLEMENT_ID, "receipt-1", "행복식당", 10_000, PAID_AT, 0, NOW
        );
        SettlementExpense manual = SettlementExpense.manual(
                SETTLEMENT_ID, "추가 비용", 1_000, 2, NOW
        );
        SettlementExpense legacyPayment = SettlementExpense.paymentHistory(
                SETTLEMENT_ID, 1L, "legacy-1", "행복식당", 10_000, null, 0, NOW
        );
        return Stream.of(
                Arguments.of("단일 결제내역", List.of(payment), "행복식당", PAID_AT),
                Arguments.of("단일 영수증", List.of(receipt), "행복식당", PAID_AT),
                Arguments.of("결제내역 두 건", List.of(payment, anotherPayment), null, null),
                Arguments.of("같은 상호의 영수증과 결제내역", List.of(receipt, payment), null, null),
                Arguments.of("수동 지출만 존재", List.of(manual), null, null),
                Arguments.of("결제내역 한 건과 수동 지출", List.of(payment, manual), "행복식당", PAID_AT),
                Arguments.of("영수증 한 건과 수동 지출", List.of(receipt, manual), "행복식당", PAID_AT),
                Arguments.of("결제일 없는 기존 결제내역", List.of(legacyPayment), "행복식당", null),
                Arguments.of("빈 목록", List.of(), null, null),
                Arguments.of("null 목록", null, null, null)
        );
    }
}
