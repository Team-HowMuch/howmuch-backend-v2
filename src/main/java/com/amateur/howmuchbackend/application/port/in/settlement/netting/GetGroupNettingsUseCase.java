package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingResult;

import java.util.*;

public interface GetGroupNettingsUseCase {
    List<GroupNettingResult> list(long groupId);

    GroupNettingResult get(long groupId, UUID nettingId);

    GroupNettingResult current(long groupId);

    GroupNettingResult getById(UUID nettingId);

    GroupNettingResult getBySource(UUID settlementId);
}
