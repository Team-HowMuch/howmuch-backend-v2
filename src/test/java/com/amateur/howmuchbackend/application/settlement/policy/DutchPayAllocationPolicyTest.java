package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand.Allocation;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@DisplayName("더치페이 N빵 정책")
class DutchPayAllocationPolicyTest {
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 18, 0);
    private final DutchPayAllocationPolicy policy = new DutchPayAllocationPolicy();

    @Test
    @DisplayName("나누어떨어지지 않는 원 단위 잔여금을 생성자에게 더한다")
    void dutchPay_remainderAssignedToCreator() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friendOne = participant(2L, "친구 1", 1);
        SettlementParticipant friendTwo = participant(3L, "친구 2", 2);

        var allocations = policy.calculate(
                settlement(),
                List.of(creator, friendOne, friendTwo),
                1_000,
                NOW
        );

        assertThat(allocations).extracting(item -> item.amount())
                .containsExactly(334L, 333L, 333L);
        assertThat(allocations).extracting(item -> item.participantId())
                .containsExactly(creator.id(), friendOne.id(), friendTwo.id());
    }

    @Test
    @DisplayName("정산 참여자가 2명 미만이면 N빵을 만들지 않는다")
    void dutchPay_participantCountBelowTwo_rejected() {
        assertThatThrownBy(() -> policy.calculate(
                settlement(),
                List.of(participant(CREATOR_ID, "생성자", 0)),
                1_000,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANTS_INSUFFICIENT
                ));
    }

    @Test
    @DisplayName("잔여금을 임의 참여자에게 넘기지 않도록 생성자 참여자가 없으면 거부한다")
    void dutchPay_creatorMissing_rejected() {
        assertThatThrownBy(() -> policy.calculate(
                settlement(),
                List.of(
                        participant(2L, "친구 1", 0),
                        participant(3L, "친구 2", 1)
                ),
                1_000,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND
                ));
    }

    @Test
    @DisplayName("참여자 전원의 금액 합계가 총액과 같으면 참여자별 금액을 돌려준다")
    void dutchPayAllocation_balancedReplacement_accepted() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friendOne = participant(2L, "친구 1", 1);
        SettlementParticipant friendTwo = participant(3L, "친구 2", 2);

        var amounts = policy.validateReplacement(
                settlement(),
                List.of(creator, friendOne, friendTwo),
                List.of(
                        allocation(creator, 500),
                        allocation(friendOne, 300),
                        allocation(friendTwo, 200)
                )
        );

        assertThat(amounts).containsOnly(
                entry(creator.id(), 500L),
                entry(friendOne.id(), 300L),
                entry(friendTwo.id(), 200L)
        );
    }

    @Test
    @DisplayName("합계가 정산 총액과 다르면 저장을 거부한다")
    void dutchPayAllocation_totalMismatch_rejected() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friend = participant(2L, "친구 1", 1);

        assertThatThrownBy(() -> policy.validateReplacement(
                settlement(),
                List.of(creator, friend),
                List.of(allocation(creator, 500), allocation(friend, 499))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_DUTCH_PAY_TOTAL_MISMATCH
                ));
    }

    @Test
    @DisplayName("참여자가 빠지면 저장을 거부한다")
    void dutchPayAllocation_missingParticipant_rejected() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friend = participant(2L, "친구 1", 1);

        assertThatThrownBy(() -> policy.validateReplacement(
                settlement(),
                List.of(creator, friend),
                List.of(allocation(creator, 1_000))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_DUTCH_PAY_ALLOCATION_REQUEST
                ));
    }

    @Test
    @DisplayName("같은 참여자가 두 번 오면 합계가 맞아도 저장을 거부한다")
    void rejectsDuplicateParticipant() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friend = participant(2L, "친구 1", 1);

        assertThatThrownBy(() -> policy.validateReplacement(
                settlement(),
                List.of(creator, friend),
                List.of(allocation(creator, 500), allocation(creator, 500))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_DUTCH_PAY_ALLOCATION_REQUEST
                ));
    }

    @Test
    @DisplayName("정산에 없는 참여자가 오면 저장을 거부한다")
    void rejectsUnknownParticipant() {
        SettlementParticipant creator = participant(CREATOR_ID, "생성자", 0);
        SettlementParticipant friend = participant(2L, "친구 1", 1);
        SettlementParticipant outsider = participant(9L, "남", 0);

        assertThatThrownBy(() -> policy.validateReplacement(
                settlement(),
                List.of(creator, friend),
                List.of(allocation(creator, 500), allocation(outsider, 500))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_DUTCH_PAY_ALLOCATION_REQUEST
                ));
    }

    private Allocation allocation(SettlementParticipant participant, long amount) {
        return new Allocation(participant.id(), amount);
    }

    private Settlement settlement() {
        return Settlement.draft(CREATOR_ID, "회식 정산", 1_000, NOW);
    }

    private SettlementParticipant participant(Long userId, String name, int order) {
        return SettlementParticipant.registeredUser(
                SETTLEMENT_ID,
                userId,
                name,
                order,
                NOW
        );
    }
}
