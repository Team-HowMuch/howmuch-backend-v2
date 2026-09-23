package com.amateur.howmuchbackend.domain.group;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("그룹 도메인")
class GroupTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Test
    @DisplayName("이름과 메모를 정규화해 활성 그룹을 생성한다")
    void group_creation_nameAndMemoNormalized() {
        Group group = Group.create("  여행 모임  ", "  여름 휴가  ", null, 1L, "AB12CD34", NOW);

        assertThat(group.name()).isEqualTo("여행 모임");
        assertThat(group.memo()).isEqualTo("여름 휴가");
        assertThat(group.ownerId()).isEqualTo(1L);
        assertThat(group.creatorId()).isEqualTo(1L);
        assertThat(group.status()).isEqualTo(GroupStatus.ACTIVE);
        assertThat(group.deletedAt()).isNull();
    }

    @Test
    @DisplayName("빈 메모는 저장하지 않는다")
    void group_blankMemo_normalizedToNull() {
        Group group = Group.create("여행", "   ", null, 1L, "AB12CD34", NOW);

        assertThat(group.memo()).isNull();
    }

    @Test
    @DisplayName("20자를 초과한 이름을 거절한다")
    void rejectsLongName() {
        assertThatThrownBy(() -> Group.create("가".repeat(21), null, null, 1L, "AB12CD34", NOW))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.INVALID_GROUP_NAME));
    }

    @Test
    @DisplayName("60자를 초과한 메모를 거절한다")
    void rejectsLongMemo() {
        assertThatThrownBy(() -> Group.create("여행", "가".repeat(61), null, 1L, "AB12CD34", NOW))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.INVALID_GROUP_MEMO));
    }

    @Test
    @DisplayName("삭제하면 이미지 참조를 비우고 삭제 시각을 기록한다")
    void softDeletesGroup() {
        Group group = Group.create("여행", null, "image.jpg", 1L, "AB12CD34", NOW);
        LocalDateTime deletedAt = NOW.plusHours(1);

        Group deleted = group.delete(deletedAt);

        assertThat(deleted.status()).isEqualTo(GroupStatus.DELETED);
        assertThat(deleted.imageFileName()).isNull();
        assertThat(deleted.deletedAt()).isEqualTo(deletedAt);
        assertThatThrownBy(deleted::requireActive)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.GROUP_NOT_FOUND));
    }

    @Test
    @DisplayName("소유권을 이전하면 OWNER와 수정 시각을 함께 변경한다")
    void transfersOwnership() {
        Group group = Group.create("여행", null, null, 1L, "AB12CD34", NOW);

        Group transferred = group.transferOwnership(2L, NOW.plusHours(1));

        assertThat(transferred.ownerId()).isEqualTo(2L);
        assertThat(transferred.updatedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(transferred.creatorId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("생성자는 활성 OWNER 멤버십이 된다")
    void createsOwnerMembership() {
        GroupMember owner = GroupMember.owner(10L, 1L, NOW);

        assertThat(owner.role()).isEqualTo(GroupMemberRole.OWNER);
        assertThat(owner.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(owner.leftAt()).isNull();
        assertThat(owner.isOwner()).isTrue();
        assertThat(owner.isActive()).isTrue();
    }
}
