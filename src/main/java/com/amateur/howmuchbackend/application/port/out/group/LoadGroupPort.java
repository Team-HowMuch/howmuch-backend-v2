package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.Group;

import java.util.Optional;

public interface LoadGroupPort {
    Optional<Group> findById(Long groupId);

    boolean existsByInviteCode(String inviteCode);
}
