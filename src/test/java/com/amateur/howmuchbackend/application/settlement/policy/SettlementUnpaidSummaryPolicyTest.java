package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementUnpaidSummaryReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementUnpaidSummaryPolicyTest {
    private final SettlementUnpaidSummaryPolicy policy = new SettlementUnpaidSummaryPolicy();

    @Test
    void unpaidSummary_partialPayment_returnsRemainingAmount() {
        assertThat(policy.summarize(List.of(payment(10_000L, 3_000))))
                .isEqualTo(new SettlementUnpaidSummaryResult(7_000, 1, 0));
    }

    @Test
    void unpaidSummary_overpayment_preservesOtherShareBalance() {
        assertThat(policy.summarize(List.of(
                payment(10_000L, 15_000), payment(5_000L, 0), payment(3_000L, 3_000)
        ))).isEqualTo(new SettlementUnpaidSummaryResult(5_000, 1, 0));
    }

    @Test
    void unpaidSummary_missingOrZeroShare_excludesParticipants() {
        assertThat(policy.summarize(List.of(payment(null, 0), payment(0L, 0))))
                .isEqualTo(new SettlementUnpaidSummaryResult(0, 0, 0));
    }

    @Test
    void unpaidSummary_partialItemAllocation_countsSettlementOnce() {
        UUID settlementId = UUID.randomUUID();
        assertThat(policy.summarize(List.of(
                new SettlementUnpaidSummaryReadModel(settlementId, SettlementStatus.REQUESTED,
                        SettlementAllocationType.ITEM_ALLOCATION, UUID.randomUUID(), 10_000L,
                        BigInteger.valueOf(3_000)),
                new SettlementUnpaidSummaryReadModel(settlementId, SettlementStatus.REQUESTED,
                        SettlementAllocationType.ITEM_ALLOCATION, UUID.randomUUID(), null, BigInteger.ZERO),
                new SettlementUnpaidSummaryReadModel(UUID.randomUUID(), SettlementStatus.REQUESTED,
                        SettlementAllocationType.ITEM_ALLOCATION, null, null, BigInteger.ZERO)
        ))).isEqualTo(new SettlementUnpaidSummaryResult(7_000, 1, 2));
    }

    @Test
    void unpaidSummary_requestedDutchPay_hasNoUnconfirmedItems() {
        assertThat(policy.summarize(List.of(new SettlementUnpaidSummaryReadModel(
                UUID.randomUUID(), SettlementStatus.REQUESTED, SettlementAllocationType.DUTCH_PAY,
                UUID.randomUUID(), 10_000L, BigInteger.ZERO
        )))).isEqualTo(new SettlementUnpaidSummaryResult(10_000, 1, 0));
    }

    @Test
    void unpaidSummary_hugeConfirmedAmount_clampsBeforeLongConversion() {
        assertThat(policy.summarize(List.of(new SettlementUnpaidSummaryReadModel(
                UUID.randomUUID(), SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY,
                UUID.randomUUID(), 10_000L, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        )))).isEqualTo(new SettlementUnpaidSummaryResult(0, 0, 0));
    }

    @Test
    void unpaidSummary_totalOverflow_rejectsWrappedAmount() {
        assertThatThrownBy(() -> policy.summarize(List.of(payment(Long.MAX_VALUE, 0), payment(1L, 0))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void unpaidSummary_empty_returnsZeros() {
        assertThat(policy.summarize(List.of()))
                .isEqualTo(new SettlementUnpaidSummaryResult(0, 0, 0));
    }

    private SettlementUnpaidSummaryReadModel payment(Long amount, long confirmed) {
        return new SettlementUnpaidSummaryReadModel(
                UUID.randomUUID(), SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY,
                UUID.randomUUID(), amount, BigInteger.valueOf(confirmed)
        );
    }
}
