package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.model.GroupImageUpload;
import com.amateur.howmuchbackend.application.group.policy.GroupImagePolicy;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.CreateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class CreateGroupService implements CreateGroupUseCase {
    private static final int MAX_INVITE_CODE_GENERATION_ATTEMPTS = 10;

    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final LoadGroupPort loadGroupPort;
    private final SaveGroupPort saveGroupPort;
    private final SaveGroupMemberPort saveGroupMemberPort;
    private final GroupImageStoragePort groupImageStoragePort;
    private final GroupImagePolicy groupImagePolicy;
    private final GroupInviteCodePolicy groupInviteCodePolicy;
    private final GroupImageTransactionSupport groupImageTransactionSupport;
    private final GroupResultAssembler groupResultAssembler;

    @Override
    @Transactional
    public GroupDetailResult create(CreateGroupCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        loadUserPort.findByIdForUpdate(actor.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        String storedImageFileName = storeImage(command.imageContent());
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            Group savedGroup = saveGroupPort.save(Group.create(
                    command.name(),
                    command.memo(),
                    storedImageFileName,
                    actor.userId(),
                    generateUniqueInviteCode(),
                    now
            ));
            GroupMember savedOwner = saveGroupMemberPort.save(GroupMember.owner(
                    savedGroup.id(),
                    actor.userId(),
                    now
            ));
            groupImageTransactionSupport.deleteOnRollback(storedImageFileName);
            return groupResultAssembler.detail(savedGroup, savedOwner, 1);
        } catch (RuntimeException exception) {
            if (storedImageFileName != null) {
                groupImageStoragePort.delete(storedImageFileName);
            }
            throw exception;
        }
    }

    private String storeImage(byte[] content) {
        if (content == null) {
            return null;
        }
        GroupImageUpload image = groupImagePolicy.validate(content);
        return groupImageStoragePort.save(image);
    }

    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < MAX_INVITE_CODE_GENERATION_ATTEMPTS; attempt++) {
            String inviteCode = groupInviteCodePolicy.generate();
            if (!loadGroupPort.existsByInviteCode(inviteCode)) {
                return inviteCode;
            }
        }
        throw ApplicationException.of(GroupStatusCode.GROUP_INVITE_CODE_GENERATION_FAILED);
    }
}
