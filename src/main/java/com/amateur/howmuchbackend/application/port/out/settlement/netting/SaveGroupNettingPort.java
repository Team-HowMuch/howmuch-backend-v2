package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.settlement.netting.GroupSettlementNetting;

import java.time.LocalDateTime;
import java.util.*;

public interface SaveGroupNettingPort {
    void create(
            GroupSettlementNetting netting,
            UUID executionId,
            NettingInput input,
            List<Account> accounts,
            LocalDateTime now);

    boolean completeIfPaid(UUID id, LocalDateTime now);

    void lockNetting(UUID id);
}
