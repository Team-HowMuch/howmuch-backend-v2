package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.AddGroupMemberUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupMembersUseCase;
import com.amateur.howmuchbackend.application.port.in.group.JoinGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.AddGroupMemberCommand;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupMemberResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupMembershipService implements JoinGroupUseCase, GetGroupMembersUseCase, AddGroupMemberUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadGroupPort loadGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final SaveGroupMemberPort saveGroupMemberPort;
    private final LoadUserPort loadUserPort;
    private final GroupInviteCodePolicy groupInviteCodePolicy;
    private final PublicIdPolicy publicIdPolicy;
    private final GroupAccessService groupAccessService;
    private final GroupResultAssembler groupResultAssembler;

    @Override
    @Transactional
    public GroupDetailResult join(JoinGroupCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        String inviteCode = groupInviteCodePolicy.normalize(command.inviteCode());
        Group group = loadGroupPort.findByInviteCode(inviteCode)
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND));
        group.requireActive();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        GroupMember member = loadGroupMemberPort
                .findByGroupIdAndUserIdForUpdate(group.id(), actor.userId())
                .map(existing -> reactivate(existing, now))
                .orElseGet(() -> saveGroupMemberPort.save(GroupMember.member(
                        group.id(),
                        actor.userId(),
                        now
                )));

        int memberCount = loadGroupMemberPort.countActiveByGroupId(group.id());
        return groupResultAssembler.detail(group, member, memberCount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupMemberResult> getMembers(Long groupId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        groupAccessService.loadActive(groupId);
        groupAccessService.requireActiveMember(groupId, actor.userId());

        List<GroupMember> members = loadGroupMemberPort.findActiveByGroupId(groupId);
        Map<Long, User> usersById = loadUserPort.findAllByIds(
                        members.stream().map(GroupMember::userId).toList()
                ).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return members.stream()
                .map(member -> toResult(member, requireActiveUser(usersById, member.userId())))
                .toList();
    }

    @Override
    @Transactional
    public GroupMemberResult add(Long groupId, AddGroupMemberCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        groupAccessService.loadActiveForUpdate(groupId);
        groupAccessService.requireOwner(groupId, actor.userId());

        String publicId = publicIdPolicy.normalize(command.publicId());
        User targetUser = loadUserPort.findByPublicId(publicId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(
                        GroupStatusCode.GROUP_MEMBER_CANDIDATE_NOT_FOUND
                ));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        GroupMember member = loadGroupMemberPort
                .findByGroupIdAndUserIdForUpdate(groupId, targetUser.id())
                .map(existing -> restore(existing, now))
                .orElseGet(() -> saveGroupMemberPort.save(GroupMember.member(
                        groupId,
                        targetUser.id(),
                        now
                )));
        return toResult(member, targetUser);
    }

    private GroupMember reactivate(GroupMember member, LocalDateTime now) {
        if (member.status() == GroupMemberStatus.ACTIVE) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE);
        }
        if (member.status() == GroupMemberStatus.REMOVED) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_REMOVED);
        }
        return saveGroupMemberPort.update(member.rejoin(now));
    }

    private GroupMember restore(GroupMember member, LocalDateTime now) {
        if (member.status() == GroupMemberStatus.ACTIVE) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE);
        }
        if (member.status() == GroupMemberStatus.LEFT) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_LEFT_REQUIRES_INVITE);
        }
        return saveGroupMemberPort.update(member.restore(now));
    }

    private User requireActiveUser(Map<Long, User> usersById, Long userId) {
        User user = usersById.get(userId);
        if (user == null || !user.active()) {
            throw new IllegalStateException("Active group member user is unavailable: " + userId);
        }
        return user;
    }

    private GroupMemberResult toResult(GroupMember member, User user) {
        return new GroupMemberResult(
                member.id(),
                user.publicId(),
                user.name(),
                member.role(),
                member.joinedAt()
        );
    }
}
