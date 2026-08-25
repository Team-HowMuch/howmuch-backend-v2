package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupMemberJpaRepository;
import com.amateur.howmuchbackend.application.port.out.group.TransferGroupOwnershipPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupOwnershipPersistenceAdapter implements TransferGroupOwnershipPort {
    private final GroupJpaRepository groupJpaRepository;
    private final GroupMemberJpaRepository groupMemberJpaRepository;

    @Override
    public void transfer(Group group, GroupMember previousOwner, GroupMember newOwner) {
        var previousOwnerEntity = groupMemberJpaRepository.findById(previousOwner.id())
                .orElseThrow(() -> missingMember(previousOwner.id()));
        previousOwnerEntity.updateFrom(previousOwner);
        groupMemberJpaRepository.saveAndFlush(previousOwnerEntity);

        var newOwnerEntity = groupMemberJpaRepository.findById(newOwner.id())
                .orElseThrow(() -> missingMember(newOwner.id()));
        newOwnerEntity.updateFrom(newOwner);
        groupMemberJpaRepository.saveAndFlush(newOwnerEntity);

        var groupEntity = groupJpaRepository.findById(group.id())
                .orElseThrow(() -> new IllegalStateException("Group not found: " + group.id()));
        groupEntity.updateFrom(group);
        groupJpaRepository.save(groupEntity);
    }

    private IllegalStateException missingMember(Long memberId) {
        return new IllegalStateException("Group member not found: " + memberId);
    }
}
