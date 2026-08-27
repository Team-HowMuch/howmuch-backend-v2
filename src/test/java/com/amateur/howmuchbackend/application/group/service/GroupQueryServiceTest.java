package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupSummaryReadModel;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 조회 서비스")
class GroupQueryServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private GroupImageStoragePort groupImageStoragePort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private GroupInviteLinkPort groupInviteLinkPort;

    private GroupQueryService service;

    @BeforeEach
    void setUp() {
        service = new GroupQueryService(
                currentActorPort,
                loadGroupMemberPort,
                groupImageStoragePort,
                groupAccessService,
                new GroupResultAssembler(groupInviteLinkPort)
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
    }

    @Test
    @DisplayName("Persistence가 제공한 최근 가입 순서를 유지하고 이미지 URL을 조립한다")
    void getsMyGroupsInPersistenceOrder() {
        when(loadGroupMemberPort.findActiveGroupSummariesByUserId(1L)).thenReturn(List.of(
                new GroupSummaryReadModel(20L, "최근 그룹", "image.jpg"),
                new GroupSummaryReadModel(10L, "이전 그룹", null)
        ));

        var result = service.getMyGroups();

        assertThat(result).extracting(item -> item.groupId()).containsExactly(20L, 10L);
        assertThat(result.getFirst().imageUrl()).isEqualTo("/api/v2/groups/20/image");
        assertThat(result.get(1).imageUrl()).isNull();
    }

    @Test
    @DisplayName("활성 멤버에게 그룹 상세와 활성 인원수를 반환한다")
    void getsGroupDetail() {
        Group group = group();
        GroupMember member = member();
        when(groupAccessService.loadActive(10L)).thenReturn(group);
        when(groupAccessService.requireActiveMember(10L, 1L)).thenReturn(member);
        when(loadGroupMemberPort.countActiveByGroupId(10L)).thenReturn(3);
        when(groupInviteLinkPort.create("AB12CD34")).thenReturn("https://invite?code=AB12CD34");

        var result = service.get(10L);

        assertThat(result.memberCount()).isEqualTo(3);
        assertThat(result.myRole()).isEqualTo(GroupMemberRole.OWNER);
        assertThat(result.inviteCode()).isEqualTo("AB12CD34");
    }

    @Test
    @DisplayName("활성 멤버가 저장된 그룹 이미지를 조회한다")
    void getsGroupImage() {
        when(groupAccessService.loadActive(10L)).thenReturn(group());
        when(groupAccessService.requireActiveMember(10L, 1L)).thenReturn(member());
        when(groupImageStoragePort.load("image.jpg"))
                .thenReturn(Optional.of(new GroupImage("image/jpeg", new byte[]{1, 2, 3})));

        GroupImage result = service.getImage(10L);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.content()).containsExactly(1, 2, 3);
    }

    private Group group() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        return new Group(10L, "여행", null, "image.jpg", 1L, "AB12CD34",
                GroupStatus.ACTIVE, 1L, now, now, null);
    }

    private GroupMember member() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        return new GroupMember(20L, 10L, 1L, GroupMemberRole.OWNER, GroupMemberStatus.ACTIVE,
                now, null, now, now);
    }
}
