package com.amateur.howmuchbackend.application.port.out.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface FinanceSyncReferenceStorePort {
    FinanceSyncReferences prepare(Long externalConnectionId, List<Long> accountIds);

    record FinanceSyncReferences(UUID connectionRef, Map<Long, UUID> accountRefs) {
        public UUID accountRef(Long accountId) {
            UUID accountRef = accountRefs.get(accountId);
            if (accountRef == null) {
                throw new IllegalArgumentException("금융 동기화 계좌 참조를 찾을 수 없습니다.");
            }
            return accountRef;
        }
    }
}
