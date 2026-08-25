package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.Group;

import java.util.Optional;

public interface LoadGroupPort {
    Optional<Group> findById(Long groupId);

    Optional<Group> findByIdForUpdate(Long groupId);

    Optional<Group> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
