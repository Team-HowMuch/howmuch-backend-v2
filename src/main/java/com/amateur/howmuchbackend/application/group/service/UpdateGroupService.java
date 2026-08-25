package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.model.GroupImageUpload;
import com.amateur.howmuchbackend.application.group.policy.GroupImagePolicy;
import com.amateur.howmuchbackend.application.port.in.group.UpdateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.UpdateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
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
public class UpdateGroupService implements UpdateGroupUseCase {
    private final CurrentActorPort currentActorPort;
    private final SaveGroupPort saveGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final GroupImageStoragePort groupImageStoragePort;
    private final GroupImagePolicy groupImagePolicy;
    private final GroupAccessService groupAccessService;
    private final GroupImageTransactionSupport groupImageTransactionSupport;
    private final GroupResultAssembler groupResultAssembler;

    @Override
    @Transactional
    public GroupDetailResult update(Long groupId, UpdateGroupCommand command) {
        validateUpdate(command);
        CurrentActor actor = currentActorPort.getCurrentActor();
        Group group = groupAccessService.loadActiveForUpdate(groupId);
        GroupMember owner = groupAccessService.requireOwner(groupId, actor.userId());

        String previousImageFileName = group.imageFileName();
        String imageFileName = previousImageFileName;
        String newlyStoredFileName = null;
        if (command.imageContent() != null) {
            GroupImageUpload image = groupImagePolicy.validate(command.imageContent());
            newlyStoredFileName = groupImageStoragePort.save(image);
            imageFileName = newlyStoredFileName;
        } else if (command.resetImage()) {
            imageFileName = null;
        }

        Group updated;
        try {
            updated = saveGroupPort.update(group.changeSettings(
                    command.name() == null ? group.name() : command.name(),
                    command.memoProvided() ? command.memo() : group.memo(),
                    imageFileName,
                    LocalDateTime.now(ZoneOffset.UTC)
            ));
        } catch (RuntimeException exception) {
            if (newlyStoredFileName != null) {
                groupImageStoragePort.delete(newlyStoredFileName);
            }
            throw exception;
        }

        groupImageTransactionSupport.replaceAfterCompletion(
                previousImageFileName,
                imageFileName,
                newlyStoredFileName
        );
        int memberCount = loadGroupMemberPort.countActiveByGroupId(groupId);
        return groupResultAssembler.detail(updated, owner, memberCount);
    }

    private void validateUpdate(UpdateGroupCommand command) {
        if (command.imageContent() != null && command.resetImage()) {
            throw ApplicationException.of(
                    GroupStatusCode.INVALID_GROUP_UPDATE,
                    "그룹 이미지 업로드와 초기화를 동시에 요청할 수 없습니다."
            );
        }
        if (command.name() == null
                && !command.memoProvided()
                && command.imageContent() == null
                && !command.resetImage()) {
            throw ApplicationException.of(GroupStatusCode.INVALID_GROUP_UPDATE);
        }
    }
}
