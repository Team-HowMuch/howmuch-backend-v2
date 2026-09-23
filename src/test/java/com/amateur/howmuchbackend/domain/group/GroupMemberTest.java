package com.amateur.howmuchbackend.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("그룹멤버 도메인")
class GroupMemberTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 20, 6, 0);
    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 8, 21, 6, 0);
    private static final LocalDateTime LEFT_AT = LocalDateTime.of(2026, 8, 22, 6, 0);
    private static final LocalDateTime REACTIVATED_AT = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Test
    @DisplayName("일반 멤버를 활성 상태로 생성한다")
    void member_newMembership_active() {
        GroupMember member = GroupMember.member(10L, 2L, JOINED_AT);

        assertThat(member.role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(member.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(member.joinedAt()).isEqualTo(JOINED_AT);
        assertThat(member.leftAt()).isNull();
    }

    @Test
    @DisplayName("LEFT 멤버의 가입 시각을 갱신해 재가입시킨다")
    void member_leftRejoin_joinTimeUpdated() {
        GroupMember rejoined = endedMember(GroupMemberStatus.LEFT).rejoin(REACTIVATED_AT);

        assertThat(rejoined.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(rejoined.joinedAt()).isEqualTo(REACTIVATED_AT);
        assertThat(rejoined.leftAt()).isNull();
        assertThat(rejoined.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("REMOVED 멤버의 가입 시각을 갱신해 OWNER 복구 상태로 만든다")
    void member_removedOwnerRestore_joinTimeUpdated() {
        GroupMember restored = endedMember(GroupMemberStatus.REMOVED).restore(REACTIVATED_AT);

        assertThat(restored.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(restored.joinedAt()).isEqualTo(REACTIVATED_AT);
        assertThat(restored.leftAt()).isNull();
        assertThat(restored.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("REMOVED 멤버는 초대 코드 재가입 전이를 사용할 수 없다")
    void member_removedInviteRejoin_rejected() {
        assertThatThrownBy(() -> endedMember(GroupMemberStatus.REMOVED).rejoin(REACTIVATED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("LEFT 멤버는 OWNER 복구 전이를 사용할 수 없다")
    void member_leftOwnerRestore_rejected() {
        assertThatThrownBy(() -> endedMember(GroupMemberStatus.LEFT).restore(REACTIVATED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("활성 OWNER와 MEMBER의 역할을 소유권 이전 상태로 변경한다")
    void member_ownershipTransfer_rolesChanged() {
        GroupMember owner = GroupMember.owner(10L, 1L, JOINED_AT);
        GroupMember member = GroupMember.member(10L, 2L, JOINED_AT);

        GroupMember demoted = owner.demoteOwner(REACTIVATED_AT);
        GroupMember promoted = member.promoteToOwner(REACTIVATED_AT);

        assertThat(demoted.role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(promoted.role()).isEqualTo(GroupMemberRole.OWNER);
        assertThat(demoted.updatedAt()).isEqualTo(REACTIVATED_AT);
        assertThat(promoted.updatedAt()).isEqualTo(REACTIVATED_AT);
    }

    @Test
    @DisplayName("자기 탈퇴와 OWNER 제거는 종료 상태를 구분해 기록한다")
    void recordsDifferentExitStatuses() {
        GroupMember member = GroupMember.member(10L, 2L, JOINED_AT);

        GroupMember left = member.leave(REACTIVATED_AT);
        GroupMember removed = member.remove(REACTIVATED_AT);

        assertThat(left.status()).isEqualTo(GroupMemberStatus.LEFT);
        assertThat(removed.status()).isEqualTo(GroupMemberStatus.REMOVED);
        assertThat(left.leftAt()).isEqualTo(REACTIVATED_AT);
        assertThat(removed.leftAt()).isEqualTo(REACTIVATED_AT);
    }

    @Test
    @DisplayName("OWNER는 멤버 종료 전이를 직접 사용할 수 없다")
    void rejectsOwnerExit() {
        GroupMember owner = GroupMember.owner(10L, 1L, JOINED_AT);

        assertThatThrownBy(() -> owner.leave(REACTIVATED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> owner.remove(REACTIVATED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    private GroupMember endedMember(GroupMemberStatus status) {
        return new GroupMember(
                20L,
                10L,
                2L,
                GroupMemberRole.MEMBER,
                status,
                JOINED_AT,
                LEFT_AT,
                CREATED_AT,
                LEFT_AT
        );
    }
}
