package com.amateur.howmuchbackend.application.port.in.group;

public interface RemoveGroupMemberUseCase {
    void remove(Long groupId, Long memberId);
}
