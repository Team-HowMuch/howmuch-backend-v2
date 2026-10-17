package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadGroupNettingPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GroupNettingAccessService {
    private final GroupAccessService access;
    private final LoadGroupPort groups;
    private final LoadGroupNettingPort nettings;

    public LoadGroupNettingPort.Netting requireAccess(long groupId, UUID id, long user) {
        var netting =
                nettings.find(groupId, id)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        var group =
                groups.findById(groupId)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        access.requireActiveMember(groupId, user);
        if (group.status() == GroupStatus.DELETED && !netting.partyIds().contains(user))
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        return netting;
    }
}
