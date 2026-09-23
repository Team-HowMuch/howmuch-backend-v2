package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupImagePolicy;
import com.amateur.howmuchbackend.application.port.in.group.command.UpdateGroupCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 설정 수정 서비스")
class UpdateGroupServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private SaveGroupPort saveGroupPort;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private GroupImageStoragePort groupImageStoragePort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private GroupImageTransactionSupport groupImageTransactionSupport;
    @Mock private GroupInviteLinkPort groupInviteLinkPort;

    private UpdateGroupService service;

    @BeforeEach
    void setUp() {
        service = new UpdateGroupService(
                currentActorPort,
                saveGroupPort,
                loadGroupMemberPort,
                groupImageStoragePort,
                new GroupImagePolicy(),
                groupAccessService,
                groupImageTransactionSupport,
                new GroupResultAssembler(groupInviteLinkPort)
        );
    }

    @Test
    @DisplayName("OWNER가 메모를 제거하고 이미지를 교체한다")
    void groupSettings_ownerRequest_removesMemoAndReplacesImage() {
        Group group = group();
        GroupMember owner = owner();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(groupAccessService.loadActiveForUpdate(10L)).thenReturn(group);
        when(groupAccessService.requireOwner(10L, 1L)).thenReturn(owner);
        when(groupImageStoragePort.save(any())).thenReturn("00000000-0000-0000-0000-000000000002.png");
        when(saveGroupPort.update(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(loadGroupMemberPort.countActiveByGroupId(10L)).thenReturn(2);
        when(groupInviteLinkPort.create("AB12CD34")).thenReturn("https://invite?code=AB12CD34");

        var result = service.update(10L, new UpdateGroupCommand(
                null,
                "",
                true,
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                false
        ));

        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        verify(saveGroupPort).update(groupCaptor.capture());
        assertThat(groupCaptor.getValue().memo()).isNull();
        assertThat(groupCaptor.getValue().imageFileName())
                .isEqualTo("00000000-0000-0000-0000-000000000002.png");
        assertThat(result.memberCount()).isEqualTo(2);
        verify(groupImageTransactionSupport).replaceAfterCompletion(
                "00000000-0000-0000-0000-000000000001.jpg",
                "00000000-0000-0000-0000-000000000002.png",
                "00000000-0000-0000-0000-000000000002.png"
        );
    }

    @Test
    @DisplayName("변경 지시가 없는 요청을 조회 전에 거절한다")
    void rejectsEmptyUpdate() {
        assertThatThrownBy(() -> service.update(10L, new UpdateGroupCommand(
                null, null, false, null, false
        ))).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.INVALID_GROUP_UPDATE));

        verify(currentActorPort, never()).getCurrentActor();
        verify(saveGroupPort, never()).update(any());
    }

    @Test
    @DisplayName("이미지 업로드와 초기화를 동시에 요청하면 거절한다")
    void rejectsImageAndResetConflict() {
        assertThatThrownBy(() -> service.update(10L, new UpdateGroupCommand(
                null, null, false, new byte[]{1}, true
        ))).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.INVALID_GROUP_UPDATE));
    }

    private Group group() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        return new Group(10L, "여행", "메모", "00000000-0000-0000-0000-000000000001.jpg",
                1L, "AB12CD34", GroupStatus.ACTIVE, 1L, now, now, null);
    }

    private GroupMember owner() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        return new GroupMember(20L, 10L, 1L, GroupMemberRole.OWNER, GroupMemberStatus.ACTIVE,
                now, null, now, now);
    }
}
