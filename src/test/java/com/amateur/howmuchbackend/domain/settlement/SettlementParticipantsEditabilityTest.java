package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementParticipantsEditabilityTest {
    @ParameterizedTest
    @EnumSource(SettlementStatus.class)
    void participants_receiptOnlyEditableInDraftOrSuspended(SettlementStatus status) {
        var settlement = settlement(status, SettlementAllocationType.ITEM_ALLOCATION);
        if (status == SettlementStatus.DRAFT || status == SettlementStatus.SUSPENDED) {
            assertThatCode(settlement::ensureParticipantsEditable).doesNotThrowAnyException();
        } else {
            assertRejected(settlement);
        }
    }

    @ParameterizedTest
    @EnumSource(SettlementStatus.class)
    void participants_dutchPayRemainsDraftOnly(SettlementStatus status) {
        var settlement = settlement(status, SettlementAllocationType.DUTCH_PAY);
        if (status == SettlementStatus.DRAFT) {
            assertThatCode(settlement::ensureParticipantsEditable).doesNotThrowAnyException();
        } else {
            assertRejected(settlement);
        }
    }

    private void assertRejected(Settlement settlement) {
        assertThatThrownBy(settlement::ensureParticipantsEditable)
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_EDITABLE));
    }

    private Settlement settlement(SettlementStatus status, SettlementAllocationType type) {
        var now = LocalDateTime.of(2026, 10, 20, 0, 0);
        return new Settlement(UUID.randomUUID(), 1L, "상태 검증", 1000, type, status, now, now);
    }
}
