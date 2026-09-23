package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.group.command.TransferGroupOwnershipCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.TransferGroupOwnershipPort;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 소유권 서비스")
class GroupOwnershipServiceTest {
    private static final Long GROUP_ID = 10L;
    private static final Long OWNER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long TARGET_MEMBER_ID = 20L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private TransferGroupOwnershipPort transferGroupOwnershipPort;

    private GroupOwnershipService service;

    @BeforeEach
    void setUp() {
        service = new GroupOwnershipService(
                currentActorPort,
                groupAccessService,
                loadGroupMemberPort,
                transferGroupOwnershipPort
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
    }

    @Test
    @DisplayName("현재 OWNER를 강등하고 활성 MEMBER에게 소유권을 이전한다")
    void groupOwnership_activeMember_transferredFromCurrentOwner() {
        GroupMember owner = member(10L, OWNER_ID, GroupMemberRole.OWNER, GroupMemberStatus.ACTIVE);
        GroupMember target = member(
                TARGET_MEMBER_ID,
                TARGET_USER_ID,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.ACTIVE
        );
        when(groupAccessService.loadActiveForUpdate(GROUP_ID)).thenReturn(group());
        when(groupAccessService.requireOwner(GROUP_ID, OWNER_ID)).thenReturn(owner);
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, TARGET_MEMBER_ID))
                .thenReturn(Optional.of(target));

        service.transfer(GROUP_ID, new TransferGroupOwnershipCommand(TARGET_MEMBER_ID));

        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        ArgumentCaptor<GroupMember> previousCaptor = ArgumentCaptor.forClass(GroupMember.class);
        ArgumentCaptor<GroupMember> targetCaptor = ArgumentCaptor.forClass(GroupMember.class);
        verify(transferGroupOwnershipPort).transfer(
                groupCaptor.capture(),
                previousCaptor.capture(),
                targetCaptor.capture()
        );
        assertThat(groupCaptor.getValue().ownerId()).isEqualTo(TARGET_USER_ID);
        assertThat(previousCaptor.getValue().role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(targetCaptor.getValue().role()).isEqualTo(GroupMemberRole.OWNER);
    }

    @Test
    @DisplayName("다른 그룹이거나 비활성인 멤버에게 소유권을 이전하지 않는다")
    void groupOwnership_invalidTarget_rejected() {
        when(groupAccessService.loadActiveForUpdate(GROUP_ID)).thenReturn(group());
        when(groupAccessService.requireOwner(GROUP_ID, OWNER_ID)).thenReturn(
                member(10L, OWNER_ID, GroupMemberRole.OWNER, GroupMemberStatus.ACTIVE)
        );
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, TARGET_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(
                GROUP_ID,
                new TransferGroupOwnershipCommand(TARGET_MEMBER_ID)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(GroupStatusCode.GROUP_OWNER_TRANSFER_TARGET_INVALID));

        verify(transferGroupOwnershipPort, never()).transfer(any(), any(), any());
    }

    private Group group() {
        return new Group(
                GROUP_ID,
                "여행",
                null,
                null,
                OWNER_ID,
                "AB12CD34",
                GroupStatus.ACTIVE,
                OWNER_ID,
                CREATED_AT,
                CREATED_AT,
                null
        );
    }

    private GroupMember member(
            Long id,
            Long userId,
            GroupMemberRole role,
            GroupMemberStatus status
    ) {
        LocalDateTime leftAt = status == GroupMemberStatus.ACTIVE ? null : CREATED_AT.plusHours(1);
        return new GroupMember(
                id,
                GROUP_ID,
                userId,
                role,
                status,
                CREATED_AT,
                leftAt,
                CREATED_AT,
                leftAt == null ? CREATED_AT : leftAt
        );
    }
}
