package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.group.mapper.GroupMemberMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupMemberJpaRepository;
import com.amateur.howmuchbackend.application.port.out.group.GroupSummaryReadModel;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GroupMemberPersistenceAdapter implements LoadGroupMemberPort, SaveGroupMemberPort {
    private final GroupMemberJpaRepository groupMemberJpaRepository;

    @Override
    public Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId) {
        return groupMemberJpaRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMemberMapper::toDomain);
    }

    @Override
    public int countActiveByGroupId(Long groupId) {
        return Math.toIntExact(groupMemberJpaRepository.countByGroupIdAndStatus(
                groupId,
                GroupMemberStatus.ACTIVE
        ));
    }

    @Override
    public List<GroupSummaryReadModel> findActiveGroupSummariesByUserId(Long userId) {
        return groupMemberJpaRepository.findActiveGroupSummariesByUserId(userId)
                .stream()
                .map(group -> new GroupSummaryReadModel(
                        group.getGroupId(),
                        group.getName(),
                        group.getImageFileName()
                ))
                .toList();
    }

    @Override
    public GroupMember save(GroupMember member) {
        return GroupMemberMapper.toDomain(
                groupMemberJpaRepository.save(GroupMemberMapper.toEntity(member))
        );
    }
}
