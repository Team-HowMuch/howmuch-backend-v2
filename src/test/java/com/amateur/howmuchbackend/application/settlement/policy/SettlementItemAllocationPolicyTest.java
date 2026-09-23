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
    private static final UUID THIRD_PARTICIPANT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0);

    private final SettlementItemAllocationPolicy policy =
            new SettlementItemAllocationPolicy();

    @Test
    @DisplayName("기존 자기 선택을 교체해도 품목 합이 1을 초과하지 않으면 허용한다")
    void itemAllocation_replacementWithoutDoubleCounting_accepted() {
        SettlementItem item = item();
        List<SettlementItemSelection> selections = List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        );

        policy.validateSelection(
                item,
                selections,
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.HALF,
                1
        );
    }

    @Test
    @DisplayName("3인분 중 2인분처럼 units와 fraction을 곱한 몫을 허용한다")
    void itemAllocation_fractionAcrossMultipleUnits_allowed() {
        policy.validateSelection(
                item(30_000),
                List.of(selection(
                        SECOND_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.THIRD
                )),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.THIRD,
                2
        );
    }

    @Test
    @DisplayName("units까지 더해 품목 한 개를 넘으면 선점 충돌로 거부한다")
    void itemAllocation_exceedsItemTotalWithUnits_rejected() {
        assertThatThrownBy(() -> policy.validateSelection(
                item(30_000),
                List.of(selection(
                        SECOND_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.HALF
                )),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.THIRD,
                2
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED));
    }

    @Test
    @DisplayName("품목 수량을 넘는 요청은 선점 충돌로 거부한다")
    void itemAllocation_unitsExceedQuantity_rejected() {
        assertThatThrownBy(() -> policy.validateSelection(
                item(),
                List.of(),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.HALF,
                3
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED));
    }

    @Test
    @DisplayName("수량이 2인 품목은 2개까지 한 사람이 가져갈 수 있다")
    void allowsTakingEveryUnitOfMultiQuantityItem() {
        policy.validateSelection(
                item(10_000, 2),
                List.of(),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.ONE,
                2
        );
    }

    @Test
    @DisplayName("남이 1개를 가져간 수량 2 품목에서 2개를 고르면 거부한다")
    void rejectsTakingMoreThanRemainingQuantity() {
        assertThatThrownBy(() -> policy.validateSelection(
                item(10_000, 2),
                List.of(selection(
                        SECOND_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.ONE
                )),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.ONE,
                2
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED));
    }

    @Test
    @DisplayName("나누어떨어지지 않아도 각자 내림값만 낸다. 차액은 생성자가 부담한다")
    void floorsEveryShare() {
        SettlementItem item = item(5_000);
        List<SettlementItemSelection> selections = List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1, NOW),
                selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1,
                        NOW.plusMinutes(1)),
                selection(THIRD_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1,
                        NOW.plusMinutes(2))
        );

        var amounts = policy.calculateSelectionAmounts(item, selections);

        assertThat(selections).extracting(selection -> amounts.get(selection.id()))
                .containsExactly(1_666L, 1_666L, 1_666L);
        assertThat(amounts.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(item.amount() - 2);
    }

    @Test
    @DisplayName("내 금액은 남이 더 고르든 말든 바뀌지 않는다")
    void keepsMyAmountStableWhileOthersSelect() {
        SettlementItem item = item(5_000);
        SettlementItemSelection mine =
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1, NOW);

        long alone = policy.calculateSelectionAmounts(item, List.of(mine)).get(mine.id());
        long withOthers = policy.calculateSelectionAmounts(item, List.of(
                mine,
                selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1,
                        NOW.plusMinutes(1)),
                selection(THIRD_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD, 1,
                        NOW.plusMinutes(2))
        )).get(mine.id());

        assertThat(alone).isEqualTo(withOthers).isEqualTo(1_666L);
    }

    @Test
    @DisplayName("품목이 덜 채워졌으면 나머지를 배분하지 않는다")
    void keepsRemainderUnassignedWhileItemIsIncomplete() {
        SettlementItem item = item(5_000);

        var amounts = policy.calculateSelectionAmounts(
                item,
                List.of(selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.THIRD))
        );

        assertThat(amounts.values()).containsExactly(1_666L);
    }

    @Test
    @DisplayName("수량 2 품목을 1개·1/2개·1/2개로 나눠 확정한다")
    void finalizesMultiQuantityItem() {
        var finalization = policy.finalizeAllocation(
                SETTLEMENT_ID,
                10_000,
                participants(),
                List.of(item(10_000, 2)),
                List.of(
                        selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.ONE),
                        selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                        selection(THIRD_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
                ),
                NOW
        );

        assertThat(finalization.valid()).isTrue();
        assertThat(finalization.shares())
                .extracting(share -> share.amount())
                .containsExactlyInAnyOrder(5_000L, 2_500L, 2_500L);
    }

    @Test
    @DisplayName("수량이 1보다 작으면 거부한다")
    void rejectsNonPositiveUnits() {
        assertThatThrownBy(() -> policy.validateSelection(
                item(),
                List.of(),
                FIRST_PARTICIPANT_ID,
                SettlementItemAllocationFraction.HALF,
                0
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION_UNITS));
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
                SettlementItemAllocationFraction.ONE,
                1
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
        return item(1_000);
    }

    private SettlementItem item(long amount) {
        return item(amount, 1);
    }

    private SettlementItem item(long amount, int quantity) {
        return new SettlementItem(
                ITEM_ID,
                SETTLEMENT_ID,
                EXPENSE_ID,
                "피자",
                quantity,
                amount,
                0,
                NOW,
                NOW
        );
    }

    private SettlementItemSelection selection(
            UUID participantId,
            SettlementItemAllocationFraction fraction
    ) {
        return selection(participantId, fraction, 1, NOW);
    }

    private SettlementItemSelection selection(
            UUID participantId,
            SettlementItemAllocationFraction fraction,
            int units,
            LocalDateTime createdAt
    ) {
        return new SettlementItemSelection(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                ITEM_ID,
                participantId,
                fraction,
                units,
                createdAt,
                createdAt
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(FIRST_PARTICIPANT_ID, 1L, "최수아", 0),
                participant(SECOND_PARTICIPANT_ID, 2L, "민선영", 1),
                participant(THIRD_PARTICIPANT_ID, 3L, "김하늘", 2)
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
                null,
                name,
                order,
                NOW,
                NOW
        );
    }
}
