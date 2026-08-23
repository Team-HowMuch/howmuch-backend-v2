package com.amateur.howmuchbackend.adapter.out.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로컬 그룹 구성원 Stub")
class LocalGroupMembersStubAdapterTest {
    private final LocalGroupMembersStubAdapter adapter = new LocalGroupMembersStubAdapter();

    @Test
    @DisplayName("그룹 ID부터 연속된 세 사용자 ID를 활성 구성원으로 제공한다")
    void loadsDeterministicGroupRoster() {
        var roster = adapter.findActiveRoster(10L).orElseThrow();

        assertThat(roster.groupId()).isEqualTo(10L);
        assertThat(roster.activeUserIds()).containsExactly(10L, 11L, 12L);
    }

    @Test
    @DisplayName("양수가 아닌 그룹 ID에는 명단을 제공하지 않는다")
    void ignoresInvalidGroupId() {
        assertThat(adapter.findActiveRoster(0L)).isEmpty();
        assertThat(adapter.findActiveRoster(null)).isEmpty();
    }
}
