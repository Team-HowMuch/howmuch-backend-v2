package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.group.mapper.GroupMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.group.repository.GroupJpaRepository;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.domain.group.Group;
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
    public boolean existsByInviteCode(String inviteCode) {
        return groupJpaRepository.existsByInviteCode(inviteCode);
    }

    @Override
    public Group save(Group group) {
        return GroupMapper.toDomain(groupJpaRepository.save(GroupMapper.toEntity(group)));
    }
}
