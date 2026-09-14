package com.amateur.howmuchbackend.application.port.out.settlement;

public interface LoadUserSettlementStatePort {
    boolean existsByUserIdAndStatusNotCompleted(Long userId);
}
