package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupSummaryResult;
import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupSummaryReadModel;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupResultAssembler {
    private static final String GROUP_IMAGE_PATH_PREFIX = "/api/v2/groups/";
    private final GroupInviteLinkPort groupInviteLinkPort;

    public GroupSummaryResult summary(GroupSummaryReadModel group) {
        return new GroupSummaryResult(
                group.groupId(),
                group.name(),
                imageUrl(group.groupId(), group.imageFileName()),
                group.memberCount(),
                group.memberNames()
        );
    }

    public GroupDetailResult detail(Group group, GroupMember member, int memberCount) {
        return new GroupDetailResult(
                group.id(),
                group.name(),
                group.memo(),
                imageUrl(group.id(), group.imageFileName()),
                memberCount,
                group.inviteCode(),
                groupInviteLinkPort.create(group.inviteCode()),
                member.role(),
                group.createdAt()
        );
    }

    private String imageUrl(Long groupId, String imageFileName) {
        return imageFileName == null ? null : GROUP_IMAGE_PATH_PREFIX + groupId + "/image";
    }
}
