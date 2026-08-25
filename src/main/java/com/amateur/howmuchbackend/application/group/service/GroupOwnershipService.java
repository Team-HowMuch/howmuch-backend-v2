package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.group.TransferGroupOwnershipUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.TransferGroupOwnershipCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.TransferGroupOwnershipPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class GroupOwnershipService implements TransferGroupOwnershipUseCase {
    private final CurrentActorPort currentActorPort;
    private final GroupAccessService groupAccessService;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final TransferGroupOwnershipPort transferGroupOwnershipPort;

    @Override
    @Transactional
    public void transfer(Long groupId, TransferGroupOwnershipCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Group group = groupAccessService.loadActiveForUpdate(groupId);
        GroupMember currentOwner = groupAccessService.requireOwner(groupId, actor.userId());
        GroupMember target = loadGroupMemberPort
                .findByGroupIdAndId(groupId, command.targetMemberId())
                .filter(this::isTransferTarget)
                .orElseThrow(() -> ApplicationException.of(
                        GroupStatusCode.GROUP_OWNER_TRANSFER_TARGET_INVALID
                ));

        if (target.userId().equals(actor.userId())) {
            throw ApplicationException.of(GroupStatusCode.GROUP_OWNER_TRANSFER_TARGET_INVALID);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        transferGroupOwnershipPort.transfer(
                group.transferOwnership(target.userId(), now),
                currentOwner.demoteOwner(now),
                target.promoteToOwner(now)
        );
    }

    private boolean isTransferTarget(GroupMember member) {
        return member.isActive() && !member.isOwner();
    }
}
