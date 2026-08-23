package com.amateur.howmuchbackend.adapter.out.group;

import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMembersPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("prod")
public class UnavailableGroupMembersAdapter implements LoadGroupMembersPort {
    @Override
    public Optional<GroupMemberRoster> findActiveRoster(Long groupId) {
        return Optional.empty();
    }
}
