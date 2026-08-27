package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.policy.GroupMemberExitPolicy;
import com.amateur.howmuchbackend.application.group.policy.GroupMemberExitType;
import com.amateur.howmuchbackend.application.port.in.group.LeaveGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.RemoveGroupMemberUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberSettlementStatePort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class GroupMemberExitService implements LeaveGroupUseCase, RemoveGroupMemberUseCase {
    private final CurrentActorPort currentActorPort;
    private final GroupAccessService groupAccessService;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final SaveGroupMemberPort saveGroupMemberPort;
    private final LoadGroupMemberSettlementStatePort loadSettlementStatePort;
    private final GroupMemberExitPolicy exitPolicy;

    @Override
    @Transactional
    public void leave(Long groupId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        groupAccessService.loadActiveForUpdate(groupId);
        GroupMember target = groupAccessService.requireActiveMember(groupId, actor.userId());
        exitPolicy.validate(
                target,
                GroupMemberExitType.SELF_LEAVE,
                actor.userId(),
                loadSettlementStatePort.findStatuses(groupId, target.userId())
        );
        saveGroupMemberPort.update(target.leave(now()));
    }

    @Override
    @Transactional
    public void remove(Long groupId, Long memberId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        groupAccessService.loadActiveForUpdate(groupId);
        groupAccessService.requireOwner(groupId, actor.userId());
        GroupMember target = loadGroupMemberPort.findByGroupIdAndId(groupId, memberId)
                .orElseThrow(() -> ApplicationException.of(
                        GroupStatusCode.GROUP_MEMBER_NOT_FOUND
                ));
        exitPolicy.validate(
                target,
                GroupMemberExitType.OWNER_REMOVE,
                actor.userId(),
                loadSettlementStatePort.findStatuses(groupId, target.userId())
        );
        saveGroupMemberPort.update(target.remove(now()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
