package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupInviteUseCase;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupInviteResult;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupInviteQueryService implements GetGroupInviteUseCase {
    private final LoadGroupPort loadGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final LoadUserPort loadUserPort;
    private final GroupInviteCodePolicy groupInviteCodePolicy;

    @Override
    @Transactional(readOnly = true)
    public GroupInviteResult get(String code) {
        String inviteCode;
        try {
            inviteCode = groupInviteCodePolicy.normalize(code);
        } catch (ApplicationException exception) {
            if (exception.getStatusCode() != GroupStatusCode.INVALID_GROUP_INVITE_CODE) {
                throw exception;
            }
            throw ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND);
        }
        Group group = loadGroupPort.findByInviteCode(inviteCode)
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND));
        group.requireActive();
        User owner = loadUserPort.findById(group.ownerId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND));
        return new GroupInviteResult(
                group.name(), owner.name(), loadGroupMemberPort.countActiveByGroupId(group.id())
        );
    }
}
