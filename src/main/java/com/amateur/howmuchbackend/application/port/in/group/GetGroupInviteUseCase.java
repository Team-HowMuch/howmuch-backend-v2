package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupInviteResult;

public interface GetGroupInviteUseCase {
    GroupInviteResult get(String code);
}
