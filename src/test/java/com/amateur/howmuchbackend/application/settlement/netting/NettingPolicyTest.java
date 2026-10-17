package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadGroupNettingPort.Transfer;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadNettingNotificationStatePort.State;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettedDepositMatchingPolicy;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingNotificationEligibilityPolicy;
import com.amateur.howmuchbackend.domain.account.*;
import com.amateur.howmuchbackend.domain.notification.NotificationType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

class NettingPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 12, 10, 0);
    private final NettedDepositMatchingPolicy matching = new NettedDepositMatchingPolicy();
    private final NettingNotificationEligibilityPolicy notifications =
            new NettingNotificationEligibilityPolicy();

    private Transfer transfer(String reference) {
        return new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                2,
                5000,
                2000,
                reference,
                20,
                "0020",
                "****1234",
                now);
    }

    private Transaction income(
            long account, String amount, LocalDateTime at, String description, String detail) {
        return new Transaction(
                1L,
                account,
                "a".repeat(64),
                AccountTransactionType.INCOME,
                new BigDecimal(amount),
                at,
                description,
                BigDecimal.ZERO,
                detail,
                null,
                null,
                null);
    }

    @Test
    void requiresSingleReferenceCorrectAccountActivationAndRemainingAmount() {
        var t = transfer("H123ABCD");
        assertThat(matching.match(income(20, "3000", now, "payer", "H123ABCD"), List.of(t)))
                .contains(t.id());
        assertThat(matching.match(income(21, "3000", now, "H123ABCD", null), List.of(t))).isEmpty();
        assertThat(
                        matching.match(
                                income(20, "3000", now.minusSeconds(1), "H123ABCD", null),
                                List.of(t)))
                .isEmpty();
        assertThat(matching.match(income(20, "3001", now, "H123ABCD", null), List.of(t))).isEmpty();
        assertThat(matching.match(income(20, "3000.1", now, "H123ABCD", null), List.of(t)))
                .isEmpty();
        assertThat(matching.match(income(20, "0", now, "H123ABCD", null), List.of(t))).isEmpty();
    }

    @Test
    void ambiguousOrTruncatedReferencesNeverAutoMatch() {
        var t = transfer("H123ABCD");
        assertThat(
                        matching.match(
                                income(20, "2000", now, "H123ABCD H7654321", null),
                                List.of(t, transfer("H7654321"))))
                .isEmpty();
        assertThat(matching.match(income(20, "2000", now, "H123ABC", null), List.of(t))).isEmpty();
        assertThat(matching.match(income(20, "2000", now, "XH123ABCD", null), List.of(t)))
                .isEmpty();
        assertThat(matching.match(income(20, "2000", now, "payer", null), List.of(t))).isEmpty();
        assertThat(matching.match(income(20, "2000", now, "h123abcd / H123ABCD", null), List.of(t)))
                .contains(t.id());
    }

    @Test
    void suppressesSupersededUnavailableReviewedAndPartiallyPaidNotifications() {
        assertThat(
                        notifications.available(
                                NotificationType.NETTED_TRANSFER_REMINDER,
                                new State(true, "ACTIVE", 0, 0L, 5000L, false)))
                .isTrue();
        assertThat(
                        notifications.available(
                                NotificationType.NETTED_TRANSFER_REMINDER,
                                new State(true, "ACTIVE", 0, 0L, 5000L, true)))
                .isFalse();
        assertThat(
                        notifications.available(
                                NotificationType.NETTED_TRANSFER_REMINDER,
                                new State(true, "ACTIVE", 1, 0L, 5000L, false)))
                .isFalse();
        assertThat(
                        notifications.available(
                                NotificationType.NETTED_TRANSFER_REMINDER,
                                new State(true, "ACTIVE", 0, 2000L, 3000L, false)))
                .isFalse();
        assertThat(
                        notifications.available(
                                NotificationType.NETTED_TRANSFER_REMINDER,
                                new State(true, "SUPERSEDED", 0, 0L, 5000L, false)))
                .isFalse();
        assertThat(
                        notifications.available(
                                NotificationType.GROUP_NETTING_COMPLETED,
                                new State(false, "COMPLETED", 0, null, null, false)))
                .isFalse();
        assertThat(
                        notifications.available(
                                NotificationType.GROUP_NETTING_CONFIRMED,
                                new State(true, "SUPERSEDED", 0, null, null, false)))
                .isFalse();
    }
}
