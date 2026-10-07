package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.GroupInvitation;
import java.util.Optional;
import java.util.UUID;

public interface GroupInvitationStorePort {
    Optional<GroupInvitation> findById(UUID id);
    Optional<GroupInvitation> findByIdForUpdate(UUID id);
    Optional<GroupInvitation> findPending(Long groupId, Long recipientUserId);
    GroupInvitation save(GroupInvitation invitation);
    GroupInvitation update(GroupInvitation invitation);
    void cancelPending(Long groupId, Long recipientUserId, java.time.LocalDateTime now);
}
