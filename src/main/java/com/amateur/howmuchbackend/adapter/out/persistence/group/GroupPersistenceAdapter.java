package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.group.mapper.GroupMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupJpaRepository;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GroupPersistenceAdapter implements LoadGroupPort, SaveGroupPort {
    private final GroupJpaRepository groupJpaRepository;

    @Override
    public Optional<Group> findById(Long groupId) {
        return groupJpaRepository.findById(groupId).map(GroupMapper::toDomain);
    }

    @Override
    public Optional<Group> findByIdForUpdate(Long groupId) {
        return groupJpaRepository.findByIdForUpdate(groupId).map(GroupMapper::toDomain);
    }

    @Override
    public Optional<Group> findByInviteCode(String inviteCode) {
        return groupJpaRepository.findByInviteCode(inviteCode).map(GroupMapper::toDomain);
    }

    @Override
    public boolean existsByInviteCode(String inviteCode) {
        return groupJpaRepository.existsByInviteCode(inviteCode);
    }

    @Override
    public Group save(Group group) {
        return GroupMapper.toDomain(groupJpaRepository.save(GroupMapper.toEntity(group)));
    }

    @Override
    public Group update(Group group) {
        var entity = groupJpaRepository.findById(group.id())
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND));
        entity.updateFrom(group);
        return GroupMapper.toDomain(groupJpaRepository.save(entity));
    }
}
