package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.JoinGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class GroupMembershipService implements JoinGroupUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadGroupPort loadGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final SaveGroupMemberPort saveGroupMemberPort;
    private final GroupInviteCodePolicy groupInviteCodePolicy;
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

    private GroupMember reactivate(GroupMember member, LocalDateTime now) {
        if (member.status() == GroupMemberStatus.ACTIVE) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE);
        }
        if (member.status() == GroupMemberStatus.REMOVED) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_REMOVED);
        }
        return saveGroupMemberPort.update(member.rejoin(now));
    }
}
