package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupImagePolicy;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 생성 서비스")
class CreateGroupServiceTest {
    private static final Long USER_ID = 1L;
    private static final Long GROUP_ID = 10L;

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private LoadGroupPort loadGroupPort;
    @Mock private SaveGroupPort saveGroupPort;
    @Mock private SaveGroupMemberPort saveGroupMemberPort;
    @Mock private GroupImageStoragePort groupImageStoragePort;
    @Mock private GroupInviteCodePolicy groupInviteCodePolicy;
    @Mock private GroupImageTransactionSupport groupImageTransactionSupport;
    @Mock private GroupInviteLinkPort groupInviteLinkPort;

    private CreateGroupService service;

    @BeforeEach
    void setUp() {
        service = new CreateGroupService(
                currentActorPort,
                loadUserPort,
                loadGroupPort,
                saveGroupPort,
                saveGroupMemberPort,
                groupImageStoragePort,
                new GroupImagePolicy(),
                groupInviteCodePolicy,
                groupImageTransactionSupport,
                new GroupResultAssembler(groupInviteLinkPort)
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadUserPort.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user()));
        when(groupInviteCodePolicy.generate()).thenReturn("AB12CD34");
        when(loadGroupPort.existsByInviteCode("AB12CD34")).thenReturn(false);
        when(saveGroupPort.save(any(Group.class)))
                .thenAnswer(invocation -> persistedGroup(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("그룹과 요청자의 OWNER 멤버십을 함께 생성한다")
    void groupCreation_request_createsGroupAndOwner() {
        when(saveGroupMemberPort.save(any(GroupMember.class)))
                .thenAnswer(invocation -> persistedMember(invocation.getArgument(0)));
        when(groupInviteLinkPort.create("AB12CD34"))
                .thenReturn("https://howmuch.app/groups/join?code=AB12CD34");

        var result = service.create(new CreateGroupCommand(" 여행 ", " 메모 ", null));

        ArgumentCaptor<GroupMember> memberCaptor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().groupId()).isEqualTo(GROUP_ID);
        assertThat(memberCaptor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(memberCaptor.getValue().role()).isEqualTo(GroupMemberRole.OWNER);
        assertThat(result.groupId()).isEqualTo(GROUP_ID);
        assertThat(result.name()).isEqualTo("여행");
        assertThat(result.memberCount()).isEqualTo(1);
        assertThat(result.myRole()).isEqualTo(GroupMemberRole.OWNER);
    }

    @Test
    @DisplayName("멤버십 저장이 실패하면 먼저 저장한 신규 이미지를 제거한다")
    void deletesNewImageWhenPersistenceFails() {
        when(groupImageStoragePort.save(any())).thenReturn("00000000-0000-0000-0000-000000000001.jpg");
        when(saveGroupMemberPort.save(any(GroupMember.class))).thenThrow(new IllegalStateException("failed"));

        assertThatThrownBy(() -> service.create(new CreateGroupCommand(
                "여행",
                null,
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        ))).isInstanceOf(IllegalStateException.class);

        verify(groupImageStoragePort).delete("00000000-0000-0000-0000-000000000001.jpg");
    }

    private Group persistedGroup(Group group) {
        return new Group(
                GROUP_ID,
                group.name(),
                group.memo(),
                group.imageFileName(),
                group.ownerId(),
                group.inviteCode(),
                group.status(),
                group.creatorId(),
                group.createdAt(),
                group.updatedAt(),
                group.deletedAt()
        );
    }

    private GroupMember persistedMember(GroupMember member) {
        return new GroupMember(
                20L,
                member.groupId(),
                member.userId(),
                member.role(),
                member.status(),
                member.joinedAt(),
                member.leftAt(),
                member.createdAt(),
                member.updatedAt()
        );
    }

    private User user() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        return new User(
                USER_ID, "identity", "최수아", "sua.test", null, null, "01012345678",
                "pin", null, null, true, UserRole.USER, true, null, now, null, now, now
        );
    }
}
