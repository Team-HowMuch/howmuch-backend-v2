package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupImageUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetMyGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupQueryService implements GetMyGroupsUseCase, GetGroupUseCase, GetGroupImageUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final GroupImageStoragePort groupImageStoragePort;
    private final GroupAccessService groupAccessService;
    private final GroupResultAssembler groupResultAssembler;

    @Override
    @Transactional(readOnly = true)
    public List<GroupSummaryResult> getMyGroups() {
        CurrentActor actor = currentActorPort.getCurrentActor();
        return loadGroupMemberPort.findActiveGroupSummariesByUserId(actor.userId())
                .stream()
                .map(groupResultAssembler::summary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GroupDetailResult get(Long groupId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Group group = groupAccessService.loadActive(groupId);
        GroupMember member = groupAccessService.requireActiveMember(groupId, actor.userId());
        int memberCount = loadGroupMemberPort.countActiveByGroupId(groupId);
        return groupResultAssembler.detail(group, member, memberCount);
    }

    @Override
    @Transactional(readOnly = true)
    public GroupImage getImage(Long groupId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Group group = groupAccessService.loadActive(groupId);
        groupAccessService.requireActiveMember(groupId, actor.userId());
        if (!group.hasImage()) {
            throw ApplicationException.of(GroupStatusCode.GROUP_IMAGE_NOT_FOUND);
        }
        return groupImageStoragePort.load(group.imageFileName())
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_IMAGE_NOT_FOUND));
    }
}
