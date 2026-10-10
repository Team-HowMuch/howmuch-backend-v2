package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementUnpaidSummaryPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SettlementUnpaidSummaryPersistenceIntegrationTest {
    @Autowired private SettlementUnpaidSummaryPersistenceAdapter adapter;
    @Autowired private SettlementUnpaidSummaryPolicy policy;
    @Autowired private JdbcTemplate jdbc;

    private SettlementUnpaidFixtures fixtures;
    private Long creatorId;
    private Long payerId;

    @BeforeEach
    void setUp() {
        fixtures = new SettlementUnpaidFixtures(jdbc);
        creatorId = fixtures.user();
        payerId = fixtures.user();
    }

    @AfterEach
    void tearDown() {
        fixtures.close();
    }

    @Test
    void unpaidSummary_multipleDeposits_sumsOnlyMatchedShareAmounts() {
        UUID settlementId = awaitingSettlement();
        UUID shareId = payableShare(settlementId, payerId, 10_000);
        fixtures.deposit(creatorId, settlementId, shareId, 2_000);
        fixtures.deposit(creatorId, settlementId, shareId, 1_000);
        fixtures.transaction(creatorId, BigDecimal.valueOf(50_000));

        assertSummary(7_000, 1, 0);
    }

    @Test
    void unpaidSummary_overpaidShare_preservesOtherParticipantsBalance() {
        UUID settlementId = awaitingSettlement();
        UUID overpaid = payableShare(settlementId, payerId, 10_000);
        UUID completed = payableShare(settlementId, fixtures.user(), 3_000);
        payableShare(settlementId, fixtures.user(), 5_000);
        fixtures.deposit(creatorId, settlementId, overpaid, 15_000);
        fixtures.deposit(creatorId, settlementId, completed, 3_000);

        assertSummary(5_000, 1, 0);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"DRAFT", "SUSPENDED", "COMPLETED"})
    void unpaidSummary_excludedStatus_ignoresAllAmounts(SettlementStatus status) {
        UUID settlementId = fixtures.settlement(creatorId, status, SettlementAllocationType.ITEM_ALLOCATION);
        payableShare(settlementId, payerId, 10_000);

        assertThat(adapter.findAllByCreatorId(creatorId)).isEmpty();
        assertSummary(0, 0, 0);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"REQUESTED", "AWAITING_DEPOSIT"})
    void unpaidSummary_deletedSettlement_excludesPendingAndConfirmedAmounts(SettlementStatus status) {
        UUID settlementId = fixtures.settlement(creatorId, status, SettlementAllocationType.ITEM_ALLOCATION);
        payableShare(settlementId, payerId, 10_000);
        fixtures.markDeleted(settlementId);

        assertSummary(0, 0, 0);
    }

    @Test
    void unpaidSummary_otherCreatorAndOwnShare_excludesBoth() {
        UUID ownSettlement = awaitingSettlement();
        payableShare(ownSettlement, creatorId, 50_000);
        payableShare(ownSettlement, payerId, 10_000);
        UUID otherSettlement = fixtures.settlement(
                payerId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
        payableShare(otherSettlement, creatorId, 20_000);

        assertSummary(10_000, 1, 0);
        assertThat(adapter.findAllByCreatorId(creatorId))
                .allSatisfy(fact -> assertThat(fact.settlementId()).isEqualTo(ownSettlement));
    }

    @Test
    void unpaidSummary_sameUserInTwoSettlements_countsTwoParticipations() {
        payableShare(awaitingSettlement(), payerId, 10_000);
        payableShare(awaitingSettlement(), payerId, 10_000);

        assertSummary(20_000, 2, 0);
    }

    @Test
    void unpaidSummary_unlinkedContacts_includesHashedAndNameOnlyParticipants() {
        UUID settlementId = awaitingSettlement();
        UUID hashed = fixtures.participant(settlementId, null, "a".repeat(64));
        fixtures.share(settlementId, hashed, 3_000);
        payableShare(settlementId, null, 2_000);

        assertSummary(5_000, 2, 0);
    }

    @Test
    void unpaidSummary_partialItemAllocation_preservesUnconfirmedSettlements() {
        UUID partial = fixtures.settlement(
                creatorId, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION
        );
        UUID shareId = payableShare(partial, payerId, 10_000);
        fixtures.deposit(creatorId, partial, shareId, 3_000);
        fixtures.submitZero(partial, fixtures.participant(partial, null));
        fixtures.participant(partial, fixtures.user());
        fixtures.settlement(creatorId, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);

        assertSummary(7_000, 1, 2);
    }

    @Test
    void unpaidSummary_submittedZeroAmount_hasNoUnpaidParticipation() {
        UUID settlementId = fixtures.settlement(
                creatorId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.ITEM_ALLOCATION
        );
        fixtures.submitZero(settlementId, fixtures.participant(settlementId, payerId));

        assertSummary(0, 0, 0);
    }

    @Test
    void unpaidSummary_fractionalConfirmedAmount_rejectsTruncation() {
        UUID settlementId = awaitingSettlement();
        UUID shareId = payableShare(settlementId, payerId, 10_000);
        fixtures.deposit(creatorId, settlementId, shareId, new BigDecimal("3000.5000"));

        assertThatThrownBy(() -> adapter.findAllByCreatorId(creatorId))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void unpaidSummary_noCreatedSettlements_returnsZeros() {
        assertSummary(0, 0, 0);
    }

    private UUID awaitingSettlement() {
        return fixtures.settlement(
                creatorId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
    }

    private UUID payableShare(UUID settlementId, Long linkedUserId, long amount) {
        return fixtures.share(settlementId, fixtures.participant(settlementId, linkedUserId), amount);
    }

    private void assertSummary(long remaining, int unpaid, int unconfirmed) {
        assertThat(policy.summarize(adapter.findAllByCreatorId(creatorId)))
                .isEqualTo(new SettlementUnpaidSummaryResult(remaining, unpaid, unconfirmed));
    }
}
