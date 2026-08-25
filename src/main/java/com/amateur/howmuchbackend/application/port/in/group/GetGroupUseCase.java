package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;

public interface GetGroupUseCase {
    GroupDetailResult get(Long groupId);
}
