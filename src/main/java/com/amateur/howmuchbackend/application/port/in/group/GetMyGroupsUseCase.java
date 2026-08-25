package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupSummaryResult;

import java.util.List;

public interface GetMyGroupsUseCase {
    List<GroupSummaryResult> getMyGroups();
}
