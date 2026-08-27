package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.group.DeleteGroupUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.domain.group.Group;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class DeleteGroupService implements DeleteGroupUseCase {
    private final CurrentActorPort currentActorPort;
    private final SaveGroupPort saveGroupPort;
    private final GroupAccessService groupAccessService;
    private final GroupImageTransactionSupport groupImageTransactionSupport;

    @Override
    @Transactional
    public void delete(Long groupId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Group group = groupAccessService.loadActiveForUpdate(groupId);
        groupAccessService.requireOwner(groupId, actor.userId());

        String previousImageFileName = group.imageFileName();
        saveGroupPort.update(group.delete(LocalDateTime.now(ZoneOffset.UTC)));
        groupImageTransactionSupport.replaceAfterCompletion(previousImageFileName, null, null);
    }
}
