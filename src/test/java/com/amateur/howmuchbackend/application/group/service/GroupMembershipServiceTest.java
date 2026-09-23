package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.command.AddGroupMemberCommand;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 멤버십 서비스")
class GroupMembershipServiceTest {
    private static final Long GROUP_ID = 10L;
    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 20, 6, 0);
    private static final LocalDateTime LEFT_AT = LocalDateTime.of(2026, 8, 21, 6, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadGroupPort loadGroupPort;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private SaveGroupMemberPort saveGroupMemberPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private ProfileImageStoragePort profileImageStoragePort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private GroupInviteLinkPort groupInviteLinkPort;

    private GroupMembershipService service;

    @BeforeEach
    void setUp() {
        service = new GroupMembershipService(
                currentActorPort,
                loadGroupPort,
                loadGroupMemberPort,
                saveGroupMemberPort,
                loadUserPort,
                profileImageStoragePort,
                new GroupInviteCodePolicy(),
                new PublicIdPolicy(),
                groupAccessService,
                new GroupResultAssembler(groupInviteLinkPort)
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(MEMBER_ID));
    }

    @Test
    @DisplayName("초대 코드로 신규 MEMBER를 생성한다")
    void groupJoin_newInviteCode_createsMember() {
        givenJoinGroup();
        givenInviteLink();
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.empty());
        when(saveGroupMemberPort.save(any(GroupMember.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0), 22L));
        when(loadGroupMemberPort.countActiveByGroupId(GROUP_ID)).thenReturn(2);

        var result = service.join(new JoinGroupCommand("  ab12cd34 "));

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(captor.getValue().status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(result.memberCount()).isEqualTo(2);
        assertThat(result.myRole()).isEqualTo(GroupMemberRole.MEMBER);
    }

    @Test
    @DisplayName("LEFT 멤버가 초대 코드로 재가입하면 가입 시각을 갱신한다")
    void groupRejoin_leftMember_updatesJoinTime() {
        givenJoinGroup();
        givenInviteLink();
        GroupMember leftMember = endedMember(GroupMemberStatus.LEFT);
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(leftMember));
        when(saveGroupMemberPort.update(any(GroupMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(loadGroupMemberPort.countActiveByGroupId(GROUP_ID)).thenReturn(2);

        service.join(new JoinGroupCommand("AB12CD34"));

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(captor.getValue().joinedAt()).isAfter(LEFT_AT);
        assertThat(captor.getValue().leftAt()).isNull();
        assertThat(captor.getValue().createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("ACTIVE 멤버의 중복 가입을 거절한다")
    void groupJoin_activeMember_rejected() {
        givenJoinGroup();
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(activeMember(20L, MEMBER_ID, GroupMemberRole.MEMBER)));

        assertStatus(
                () -> service.join(new JoinGroupCommand("AB12CD34")),
                GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE
        );
    }

    @Test
    @DisplayName("REMOVED 멤버의 초대 코드 가입을 거절한다")
    void groupJoin_removedMember_rejected() {
        givenJoinGroup();
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(endedMember(GroupMemberStatus.REMOVED)));

        assertStatus(
                () -> service.join(new JoinGroupCommand("AB12CD34")),
                GroupStatusCode.GROUP_MEMBER_REMOVED
        );
    }

    @Test
    @DisplayName("활성 멤버 목록을 OWNER 우선 순서와 현재 사용자 정보로 반환한다")
    void groupMembers_activeList_ownerFirstAndCurrentUserIncluded() {
        GroupMember owner = activeMember(10L, OWNER_ID, GroupMemberRole.OWNER);
        GroupMember member = activeMember(20L, MEMBER_ID, GroupMemberRole.MEMBER);
        when(loadGroupMemberPort.findActiveByGroupId(GROUP_ID)).thenReturn(List.of(owner, member));
        when(loadUserPort.findAllByIds(any())).thenReturn(List.of(
                user(MEMBER_ID, "member.test", "멤버", true),
                user(OWNER_ID, "owner.test", "방장", true)
        ));

        var results = service.getMembers(GROUP_ID);

        verify(groupAccessService).loadActive(GROUP_ID);
        verify(groupAccessService).requireActiveMember(GROUP_ID, MEMBER_ID);
        assertThat(results).extracting(result -> result.publicId())
                .containsExactly("owner.test", "member.test");
        assertThat(results.getFirst().role()).isEqualTo(GroupMemberRole.OWNER);
    }

    @Test
    @DisplayName("사진을 올린 멤버만 파일명 기반 아바타 버전을 반환한다")
    void marksMembersWithAvatar() {
        GroupMember owner = activeMember(10L, OWNER_ID, GroupMemberRole.OWNER);
        GroupMember member = activeMember(20L, MEMBER_ID, GroupMemberRole.MEMBER);
        when(loadGroupMemberPort.findActiveByGroupId(GROUP_ID)).thenReturn(List.of(owner, member));
        when(loadUserPort.findAllByIds(any())).thenReturn(List.of(
                user(OWNER_ID, "owner.test", "방장", true, "owner.webp"),
                user(MEMBER_ID, "member.test", "멤버", true)
        ));

        var results = service.getMembers(GROUP_ID);

        assertThat(results).extracting(result -> result.avatarVersion())
                .containsExactly(
                        ImageCacheVersion.fromNullableFileName("owner.webp"),
                        null
                );
    }

    @Test
    @DisplayName("같은 그룹 멤버의 아바타 파일을 반환한다")
    void getsMemberAvatar() {
        ProfileImage image = new ProfileImage("image/webp", new byte[]{1, 2, 3});
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, 20L))
                .thenReturn(Optional.of(activeMember(20L, MEMBER_ID, GroupMemberRole.MEMBER)));
        when(loadUserPort.findById(MEMBER_ID))
                .thenReturn(Optional.of(user(MEMBER_ID, "member.test", "멤버", true, "member.webp")));
        when(profileImageStoragePort.load("member.webp")).thenReturn(Optional.of(image));

        ProfileImage result = service.getMemberAvatar(GROUP_ID, 20L);

        verify(groupAccessService).requireActiveMember(GROUP_ID, MEMBER_ID);
        assertThat(result).isEqualTo(image);
    }

    @Test
    @DisplayName("그룹의 활성 멤버가 아니면 아바타를 조회할 수 없다")
    void rejectsAvatarOfUnknownMember() {
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, 99L)).thenReturn(Optional.empty());

        assertStatus(
                () -> service.getMemberAvatar(GROUP_ID, 99L),
                GroupStatusCode.GROUP_MEMBER_NOT_FOUND
        );
    }

    @Test
    @DisplayName("사진을 올리지 않은 멤버의 아바타 조회는 실패한다")
    void rejectsAvatarWithoutFile() {
        when(loadGroupMemberPort.findByGroupIdAndId(GROUP_ID, 20L))
                .thenReturn(Optional.of(activeMember(20L, MEMBER_ID, GroupMemberRole.MEMBER)));
        when(loadUserPort.findById(MEMBER_ID))
                .thenReturn(Optional.of(user(MEMBER_ID, "member.test", "멤버", true)));

        assertThatThrownBy(() -> service.getMemberAvatar(GROUP_ID, 20L))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("OWNER가 publicId로 신규 멤버를 직접 추가한다")
    void addsNewMemberByPublicId() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
        User target = user(MEMBER_ID, "member.test", "멤버", true);
        when(loadUserPort.findByPublicId("member.test")).thenReturn(Optional.of(target));
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.empty());
        when(saveGroupMemberPort.save(any(GroupMember.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0), 20L));

        var result = service.add(GROUP_ID, new AddGroupMemberCommand(" MEMBER.TEST "));

        verify(groupAccessService).loadActiveForUpdate(GROUP_ID);
        verify(groupAccessService).requireOwner(GROUP_ID, OWNER_ID);
        assertThat(result.memberId()).isEqualTo(20L);
        assertThat(result.publicId()).isEqualTo("member.test");
        assertThat(result.role()).isEqualTo(GroupMemberRole.MEMBER);
    }

    @Test
    @DisplayName("OWNER가 REMOVED 멤버를 직접 복구한다")
    void restoresRemovedMember() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
        User target = user(MEMBER_ID, "member.test", "멤버", true);
        when(loadUserPort.findByPublicId("member.test")).thenReturn(Optional.of(target));
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(endedMember(GroupMemberStatus.REMOVED)));
        when(saveGroupMemberPort.update(any(GroupMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.add(GROUP_ID, new AddGroupMemberCommand("member.test"));

        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(captor.getValue().joinedAt()).isAfter(LEFT_AT);
    }

    @Test
    @DisplayName("OWNER는 LEFT 멤버를 직접 복귀시킬 수 없다")
    void rejectsDirectRestoreForLeftMember() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
        when(loadUserPort.findByPublicId("member.test"))
                .thenReturn(Optional.of(user(MEMBER_ID, "member.test", "멤버", true)));
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(GROUP_ID, MEMBER_ID))
                .thenReturn(Optional.of(endedMember(GroupMemberStatus.LEFT)));

        assertStatus(
                () -> service.add(GROUP_ID, new AddGroupMemberCommand("member.test")),
                GroupStatusCode.GROUP_MEMBER_LEFT_REQUIRES_INVITE
        );
    }

    @Test
    @DisplayName("비활성 사용자는 직접 추가 후보로 사용하지 않는다")
    void rejectsInactiveCandidate() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OWNER_ID));
        when(loadUserPort.findByPublicId("member.test"))
                .thenReturn(Optional.of(user(MEMBER_ID, "member.test", "멤버", false)));

        assertStatus(
                () -> service.add(GROUP_ID, new AddGroupMemberCommand("member.test")),
                GroupStatusCode.GROUP_MEMBER_CANDIDATE_NOT_FOUND
        );
    }

    private void givenJoinGroup() {
        when(loadUserPort.findByIdForUpdate(MEMBER_ID))
                .thenReturn(Optional.of(user(MEMBER_ID, "member.test", "멤버", true)));
        when(loadGroupPort.findByInviteCodeForUpdate("AB12CD34")).thenReturn(Optional.of(group()));
    }

    private void givenInviteLink() {
        when(groupInviteLinkPort.create("AB12CD34"))
                .thenReturn("https://howmuch.app/groups/join?code=AB12CD34");
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

    private GroupMember activeMember(Long id, Long userId, GroupMemberRole role) {
        return new GroupMember(
                id,
                GROUP_ID,
                userId,
                role,
                GroupMemberStatus.ACTIVE,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }

    private GroupMember endedMember(GroupMemberStatus status) {
        return new GroupMember(
                20L,
                GROUP_ID,
                MEMBER_ID,
                GroupMemberRole.MEMBER,
                status,
                CREATED_AT,
                LEFT_AT,
                CREATED_AT,
                LEFT_AT
        );
    }

    private GroupMember persisted(GroupMember member, Long id) {
        return new GroupMember(
                id,
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

    private User user(Long id, String publicId, String name, boolean active) {
        return user(id, publicId, name, active, null);
    }

    private User user(Long id, String publicId, String name, boolean active, String avatarFileName) {
        return new User(
                id, "identity-" + id, name, publicId, null, null, "0100000000" + id,
                "pin", null, avatarFileName, true, UserRole.USER, active, null,
                CREATED_AT, null, CREATED_AT, CREATED_AT
        );
    }

    private void assertStatus(Runnable action, GroupStatusCode statusCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(statusCode));
    }
}
