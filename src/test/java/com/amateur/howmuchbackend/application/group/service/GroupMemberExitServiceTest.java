package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupMemberExitPolicy;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInvitationStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberSettlementStatePort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹멤버 종료 서비스")
class GroupMemberExitServiceTest {
    private static final Long GROUP_ID = 10L;
    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_USER_ID = 2L;
    private static final Long MEMBER_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Mock private BusinessNotificationService notifications;
    @Mock private GroupInvitationStorePort invitations;
    @Mock private CurrentActorPort currentActorPort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private SaveGroupMemberPort saveGroupMemberPort;
    @Mock private LoadGroupMemberSettlementStatePort loadSettlementStatePort;

    private GroupMemberExitService service;

    @BeforeEach
    void setUp() {
        service = new GroupMemberExitService(
                currentActorPort,
                groupAccessService,
                loadGroupMemberPort,
                saveGroupMemberPort,
                loadSettlementStatePort,
                new GroupMemberExitPolicy(),
                notifications,
                invitations
        );
    }

    @Test
    @DisplayName("MEMBER 자기 탈퇴는 LEFT 상태로 저장한다")
    void groupMembership_memberSelfLeave_savedAsLeft() {
        GroupMember member = member(MEMBER_ID, MEMBER_USER_ID, GroupMemberRole.MEMBER);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(MEMBER_USER_ID));
        when(groupAccessService.requireActiveMember(GROUP_ID, MEMBER_USER_ID)).thenReturn(member);
        when(loadSettlementStatePort.findStatuses(GROUP_ID, MEMBER_USER_ID)).thenReturn(Set.of());

        service.leave(GROUP_ID);

        verify(groupAccessService).loadActiveForUpdate(GROUP_ID);
        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(GroupMemberStatus.LEFT);
        assertThat(captor.getValue().leftAt()).isNotNull();
        verify(notifications).groupMemberLeft(org.mockito.ArgumentMatchers.eq(GROUP_ID), org.mockito.ArgumentMatchers.eq(MEMBER_ID), org.mockito.ArgumentMatchers.eq(MEMBER_USER_ID), any());
    }

    @Test
    @DisplayName("OWNER가 활성 MEMBER를 제거하면 REMOVED 상태로 저장한다")
    void groupMembership_ownerRemoval_savedAsRemoved() {
        GroupMember owner = member(10L, OWNER_ID, GroupMemberRole.OWNER);
        GroupMember target = member(MEMBER_ID, MEMBER_USER_ID, GroupMemberRole.MEMBER);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
        when(groupAccessService.requireOwner(GROUP_ID, OWNER_ID)).thenReturn(owner);
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(target));
        when(loadSettlementStatePort.findStatuses(GROUP_ID, MEMBER_USER_ID)).thenReturn(Set.of());

        service.remove(GROUP_ID, MEMBER_ID);

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(GroupMemberStatus.REMOVED);
    }

    @Test
    @DisplayName("차단 정산이 있으면 멤버 상태를 변경하지 않는다")
    void groupMemberExit_ongoingSettlement_keepsMembershipUnchanged() {
        GroupMember target = member(MEMBER_ID, MEMBER_USER_ID, GroupMemberRole.MEMBER);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(MEMBER_USER_ID));
        when(groupAccessService.requireActiveMember(GROUP_ID, MEMBER_USER_ID)).thenReturn(target);
        when(loadSettlementStatePort.findStatuses(GROUP_ID, MEMBER_USER_ID))
                .thenReturn(Set.of(SettlementStatus.REQUESTED));

        assertThatThrownBy(() -> service.leave(GROUP_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                GroupStatusCode.GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT
                        ));

        verify(saveGroupMemberPort, never()).update(any());
    }

    private GroupMember member(Long id, Long userId, GroupMemberRole role) {
        return new GroupMember(
                id,
                GROUP_ID,
                userId,
                role,
                GroupMemberStatus.ACTIVE,
                NOW,
                null,
                NOW,
                NOW
        );
    }
}
