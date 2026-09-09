package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.group.mapper.GroupMemberMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupMemberJpaRepository;
import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.out.group.GroupSummaryReadModel;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMembersPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GroupMemberPersistenceAdapter implements
        LoadGroupMemberPort,
        LoadGroupMembersPort,
        LockGroupMemberRosterPort,
        SaveGroupMemberPort {
    private final GroupMemberJpaRepository groupMemberJpaRepository;
    private final GroupJpaRepository groupJpaRepository;

    @Override
    public Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId) {
        return groupMemberJpaRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMemberMapper::toDomain);
    }

    @Override
    public Optional<GroupMember> findByGroupIdAndUserIdForUpdate(Long groupId, Long userId) {
        return groupMemberJpaRepository.findByGroupIdAndUserIdForUpdate(groupId, userId)
                .map(GroupMemberMapper::toDomain);
    }

    @Override
    public Optional<GroupMember> findByGroupIdAndId(Long groupId, Long memberId) {
        return groupMemberJpaRepository.findByGroupIdAndId(groupId, memberId)
                .map(GroupMemberMapper::toDomain);
    }

    @Override
    public List<GroupMember> findActiveByGroupId(Long groupId) {
        return groupMemberJpaRepository.findAllByGroupIdAndStatus(groupId, GroupMemberStatus.ACTIVE)
                .stream()
                .map(GroupMemberMapper::toDomain)
                .sorted(Comparator
                        .comparing((GroupMember member) -> !member.isOwner())
                        .thenComparing(GroupMember::joinedAt)
                        .thenComparing(GroupMember::id))
                .toList();
    }

    @Override
    public Optional<GroupMemberRoster> findActiveRoster(Long groupId) {
        if (groupId == null || groupId <= 0) {
            return Optional.empty();
        }
        List<GroupMemberJpaRepository.GroupRosterProjection> roster =
                groupMemberJpaRepository.findActiveRoster(groupId);
        if (roster.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GroupMemberRoster(groupId, roster.stream()
                .map(GroupMemberJpaRepository.GroupRosterProjection::getUserId)
                .filter(java.util.Objects::nonNull)
                .toList()));
    }

    @Override
    public Optional<GroupMemberRoster> lockAndLoad(Long groupId) {
        if (groupId == null || groupId <= 0) {
            return Optional.empty();
        }
        if (groupJpaRepository.findByIdForUpdate(groupId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GroupMemberRoster(
                groupId,
                findActiveByGroupId(groupId).stream().map(GroupMember::userId).toList()
        ));
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
                        group.getImageFileName(),
                        Math.toIntExact(group.getMemberCount()),
                        java.util.stream.Stream.of(group.getFirstMemberName(),
                                        group.getSecondMemberName(), group.getThirdMemberName())
                                .filter(java.util.Objects::nonNull)
                                .toList()
                ))
                .toList();
    }

    @Override
    public GroupMember save(GroupMember member) {
        try {
            return GroupMemberMapper.toDomain(
                    groupMemberJpaRepository.saveAndFlush(GroupMemberMapper.toEntity(member))
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_group_members_group_user")) {
                throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_ALREADY_ACTIVE, exception);
            }
            throw exception;
        }
    }

    @Override
    public GroupMember update(GroupMember member) {
        var entity = groupMemberJpaRepository.findById(member.id())
                .orElseThrow(() -> new IllegalStateException("Group member not found: " + member.id()));
        entity.updateFrom(member);
        return GroupMemberMapper.toDomain(groupMemberJpaRepository.save(entity));
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
