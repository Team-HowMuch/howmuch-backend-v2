package com.amateur.howmuchbackend.application.group.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("그룹멤버 종료 정책")
class GroupMemberExitPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 6, 0);
    private final GroupMemberExitPolicy policy = new GroupMemberExitPolicy();

    @Test
    @DisplayName("관련 정산이 없거나 DRAFT와 COMPLETED뿐이면 종료를 허용한다")
    void groupMemberExit_noBlockingSettlement_allowed() {
        GroupMember member = activeMember(2L, GroupMemberRole.MEMBER);

        assertThatCode(() -> policy.validate(
                member,
                GroupMemberExitType.SELF_LEAVE,
                2L,
                Set.of(SettlementStatus.DRAFT, SettlementStatus.COMPLETED)
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {
            "REQUESTED", "AWAITING_DEPOSIT", "SUSPENDED"
    })
    @DisplayName("진행 중인 정산 상태가 하나라도 있으면 종료를 차단한다")
    void groupMemberExit_ongoingSettlement_blocksExit(SettlementStatus status) {
        assertStatus(
                () -> policy.validate(
                        activeMember(2L, GroupMemberRole.MEMBER),
                        GroupMemberExitType.SELF_LEAVE,
                        2L,
                        Set.of(SettlementStatus.COMPLETED, status)
                ),
                GroupStatusCode.GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT
        );
    }

    @Test
    @DisplayName("OWNER가 이전 없이 탈퇴하거나 자신을 제거하는 요청을 차단한다")
    void requiresOwnershipTransferBeforeOwnerExit() {
        GroupMember owner = activeMember(1L, GroupMemberRole.OWNER);

        assertStatus(
                () -> policy.validate(
                        owner,
                        GroupMemberExitType.SELF_LEAVE,
                        1L,
                        Set.of()
                ),
                GroupStatusCode.GROUP_OWNER_TRANSFER_REQUIRED
        );
        assertStatus(
                () -> policy.validate(
                        owner,
                        GroupMemberExitType.OWNER_REMOVE,
                        1L,
                        Set.of()
                ),
                GroupStatusCode.GROUP_OWNER_TRANSFER_REQUIRED
        );
    }

    private GroupMember activeMember(Long userId, GroupMemberRole role) {
        return new GroupMember(
                userId * 10,
                10L,
                userId,
                role,
                GroupMemberStatus.ACTIVE,
                NOW,
                null,
                NOW,
                NOW
        );
    }

    private void assertStatus(Runnable action, GroupStatusCode statusCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(statusCode));
    }
}
