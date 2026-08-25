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
    void createsMember() {
        GroupMember member = GroupMember.member(10L, 2L, JOINED_AT);

        assertThat(member.role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(member.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(member.joinedAt()).isEqualTo(JOINED_AT);
        assertThat(member.leftAt()).isNull();
    }

    @Test
    @DisplayName("LEFT 멤버의 가입 시각을 갱신해 재가입시킨다")
    void rejoinsLeftMember() {
        GroupMember rejoined = endedMember(GroupMemberStatus.LEFT).rejoin(REACTIVATED_AT);

        assertThat(rejoined.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(rejoined.joinedAt()).isEqualTo(REACTIVATED_AT);
        assertThat(rejoined.leftAt()).isNull();
        assertThat(rejoined.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("REMOVED 멤버의 가입 시각을 갱신해 OWNER 복구 상태로 만든다")
    void restoresRemovedMember() {
        GroupMember restored = endedMember(GroupMemberStatus.REMOVED).restore(REACTIVATED_AT);

        assertThat(restored.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(restored.joinedAt()).isEqualTo(REACTIVATED_AT);
        assertThat(restored.leftAt()).isNull();
        assertThat(restored.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("REMOVED 멤버는 초대 코드 재가입 전이를 사용할 수 없다")
    void rejectsRejoinForRemovedMember() {
        assertThatThrownBy(() -> endedMember(GroupMemberStatus.REMOVED).rejoin(REACTIVATED_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("LEFT 멤버는 OWNER 복구 전이를 사용할 수 없다")
    void rejectsRestoreForLeftMember() {
        assertThatThrownBy(() -> endedMember(GroupMemberStatus.LEFT).restore(REACTIVATED_AT))
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
