package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("품목별 배분 정책")
class SettlementItemAllocationPolicyTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final UUID EXPENSE_ID = UUID.randomUUID();
    private static final UUID FIRST_PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID SECOND_PARTICIPANT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0);

    private final SettlementItemAllocationPolicy policy =
            new SettlementItemAllocationPolicy();

    @Test
    @DisplayName("기존 자기 선택을 교체해도 품목 합이 1을 초과하지 않으면 허용한다")
    void validatesReplacementWithoutDoubleCountingCurrentParticipant() {
        SettlementItem item = item();
        List<SettlementItemSelection> selections = List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        );

        policy.validateSelection(
                item,
                selections,
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.HALF
        );
    }

    @Test
    @DisplayName("다른 참여자 선택과 합쳐 1을 초과하면 거부한다")
    void rejectsOverAllocation() {
        assertThatThrownBy(() -> policy.validateSelection(
                item(),
                List.of(selection(
                        SECOND_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.HALF
                )),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.ONE
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED
                ));
    }

    @Test
    @DisplayName("모든 품목이 정확히 배분되면 양수 Share를 생성한다")
    void finalizesExactAllocation() {
        var finalization = policy.finalizeAllocation(
                SETTLEMENT_ID,
                1_000,
                participants(),
                List.of(item()),
                List.of(
                        selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                        selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
                ),
                NOW
        );

        assertThat(finalization.valid()).isTrue();
        assertThat(finalization.invalidItemIds()).isEmpty();
        assertThat(finalization.shares())
                .extracting(share -> share.amount())
                .containsExactlyInAnyOrder(500L, 500L);
    }

    @Test
    @DisplayName("전체 제출 후에도 반만 배분된 품목은 중단 대상으로 반환한다")
    void marksUnderAllocatedItemsInvalid() {
        var finalization = policy.finalizeAllocation(
                SETTLEMENT_ID,
                1_000,
                participants(),
                List.of(item()),
                List.of(selection(
                        FIRST_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.HALF
                )),
                NOW
        );

        assertThat(finalization.valid()).isFalse();
        assertThat(finalization.shares()).isEmpty();
        assertThat(finalization.invalidItemIds()).containsExactly(ITEM_ID);
    }

    private SettlementItem item() {
        return new SettlementItem(
                ITEM_ID,
                SETTLEMENT_ID,
                EXPENSE_ID,
                "피자",
                1,
                1_000,
                0,
                NOW,
                NOW
        );
    }

    private SettlementItemSelection selection(
            UUID participantId,
            SettlementItemAllocationFraction fraction
    ) {
        return new SettlementItemSelection(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                ITEM_ID,
                participantId,
                fraction,
                NOW,
                NOW
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(FIRST_PARTICIPANT_ID, 1L, "최수아", 0),
                participant(SECOND_PARTICIPANT_ID, 2L, "민선영", 1)
        );
    }

    private SettlementParticipant participant(
            UUID id,
            Long linkedUserId,
            String name,
            int order
    ) {
        return new SettlementParticipant(
                id,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                linkedUserId,
                null,
                name,
                order,
                NOW,
                NOW
        );
    }
}
