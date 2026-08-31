package com.amateur.howmuchbackend.application.port.out.account;

import java.util.List;

public interface AccountPreferenceStorePort {
    void setDefault(Long userId, Long accountId);

    void updateDisplayOrders(Long userId, List<Long> orderedAccountIds);
}
