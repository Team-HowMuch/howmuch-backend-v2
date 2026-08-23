package com.amateur.howmuchbackend.adapter.out.group;

import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMembersPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile({"local", "dev"})
public class LocalGroupMembersStubAdapter implements LoadGroupMembersPort {
    @Override
    public Optional<GroupMemberRoster> findActiveRoster(Long groupId) {
        if (groupId == null || groupId <= 0) {
            return Optional.empty();
        }
        return Optional.of(new GroupMemberRoster(
                groupId,
                List.of(groupId, groupId + 1, groupId + 2)
        ));
    }
}
