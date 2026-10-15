package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementWidgetSummaryPolicyTest {
    private final SettlementWidgetSummaryPolicy policy = new SettlementWidgetSummaryPolicy();
    private final UUID id = UUID.randomUUID();

    @Test
    void emptySummaryHasNoArtificialReconciliationTimestamp() {
        var result = policy.summarize(List.of(), Map.of(), null);
        assertThat(result.receivable().totalAmount()).isZero();
        assertThat(result.receivable().totalCount()).isZero();
        assertThat(result.payable().totalAmount()).isZero();
        assertThat(result.lastReconciledAt()).isNull();
    }

    @Test
    void overpaymentsClampPerShareAndCannotOffsetAnotherPayer() {
        var result = policy.summarize(List.of(
                row(true, false, false, 1_000L, BigInteger.valueOf(2_000), false),
                row(true, false, false, 1_000L, BigInteger.valueOf(400), false)), Map.of(), null);
        assertThat(result.receivable().totalAmount()).isEqualTo(600);
        assertThat(result.receivable().totalCount()).isOne();
        assertThat(result.receivable().settlements().getFirst().unpaidParticipantCount()).isOne();
    }

    @Test
    void requestedReceivableUsesPreviewAndCountsUnsubmittedButNotSubmittedZero() {
        var result = policy.summarize(List.of(
                row(true, true, false, 1_000L, BigInteger.valueOf(200), true),
                row(true, true, false, null, BigInteger.ZERO, false),
                row(true, true, false, null, BigInteger.ZERO, true)), Map.of(id, 5_000L), null);
        assertThat(result.receivable().totalAmount()).isEqualTo(4_800);
        var receive = result.receivable().settlements().getFirst();
        assertThat(receive.unpaidParticipantCount()).isEqualTo(2);
        assertThat(receive.amountUnconfirmed()).isTrue();
    }

    @Test
    void submittedDiscountAdjustmentIsWaitingWithUnknownAmount() {
        var result = policy.summarize(List.of(row(false, true, true, null, BigInteger.ZERO, true)), Map.of(), null);
        var pay = result.payable().settlements().getFirst();
        assertThat(pay.status().name()).isEqualTo("WAITING");
        assertThat(pay.remainingAmount()).isNull();
        assertThat(result.payable().totalAmount()).isZero();
    }

    @Test
    void confirmedShareCanBePayableWhileOtherParticipantsStillSelect() {
        var result = policy.summarize(List.of(row(false, true, false, 1_000L, BigInteger.valueOf(300), true)), Map.of(), null);
        var pay = result.payable().settlements().getFirst();
        assertThat(pay.status().name()).isEqualTo("PAYABLE");
        assertThat(pay.remainingAmount()).isEqualTo(700);
    }

    @Test
    void numericDepositAggregationDoesNotOverflowBeforeClamping() {
        var result = policy.summarize(List.of(row(true, false, false, 1_000L,
                BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN), true)), Map.of(), null);
        assertThat(result.receivable().totalAmount()).isZero();
        assertThat(result.receivable().settlements().getFirst().unpaidParticipantCount()).isZero();
    }

    private SettlementWidgetReadModel row(boolean creator, boolean requested, boolean adjustment,
            Long share, BigInteger confirmed, boolean submitted) {
        return new SettlementWidgetReadModel(id, "검증", 10_000, creator,
                requested ? SettlementStatus.REQUESTED : SettlementStatus.AWAITING_DEPOSIT,
                requested ? SettlementAllocationType.ITEM_ALLOCATION : SettlementAllocationType.DUTCH_PAY,
                null, UUID.randomUUID(), share, confirmed, submitted, adjustment);
    }
}
